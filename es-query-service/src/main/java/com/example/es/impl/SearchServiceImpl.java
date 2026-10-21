package com.example.es.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.ObjectBuilder;
import com.example.es.core.Agg;
import com.example.es.exception.EsException;
import com.example.es.util.QueryBuilderUtil;
import com.example.es.dto.AggResult;
import com.example.es.dto.Condition;
import com.example.es.dto.HitWrapper;
import com.example.es.dto.SearchRequestDto;
import com.example.es.dto.SearchResultDto;
import com.example.es.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * SearchService 默认实现。依赖官方 ElasticsearchClient（elasticsearch-java 9.x）。
 */
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final ElasticsearchClient client;

    public SearchServiceImpl(ElasticsearchClient client) {
        this.client = client;
    }

    // ==================== 查询 ====================

    @Override
    public <T> List<T> search(SearchRequestDto dto, Class<T> clazz) {
        return searchPage(dto, clazz).documents;
    }

    @Override
    public <T> SearchResultDto<T> searchPage(SearchRequestDto dto, Class<T> clazz) {
        long start = System.currentTimeMillis();
        try {
            SearchResponse<T> resp = client.search(toRequest(dto, null), clazz);
            return toResult(resp, dto, System.currentTimeMillis() - start);
        } catch (IOException e) {
            throw new EsException("查询失败 index=" + dto.index, e);
        }
    }

    @Override
    public <T> SearchResultDto<T> paginate(String index, Function<Query.Builder, ObjectBuilder<Query>> queryBuilder,
                                          int pageNo, int pageSize, Class<T> clazz) {
        SearchRequestDto dto = new SearchRequestDto(index);
        dto.page(pageNo, pageSize);
        dto.sortDesc("_score");
        dto.rawQuery = queryBuilder == null ? Query.of(q -> q.matchAll(m -> m)) : Query.of(queryBuilder);
        return searchPage(dto, clazz);
    }

    @Override
    public <T> SearchResultDto<T> searchAfter(SearchRequestDto dto, Class<T> clazz) {
        if (dto.sorts.isEmpty()) {
            // search_after 必须有确定性排序，补 _shard_doc 保证稳定且高效
            dto.sortAsc("_shard_doc");
        }
        return searchPage(dto, clazz);
    }

    @Override
    public long count(SearchRequestDto dto) {
        try {
            Query query = QueryBuilderUtil.buildDto(dto);
            CountRequest req = CountRequest.of(c -> c.index(dto.index).query(query));
            return client.count(req).count();
        } catch (IOException e) {
            throw new EsException("count 失败 index=" + dto.index, e);
        }
    }

    @Override
    public <T> long searchAll(SearchRequestDto dto, int batch, Class<T> clazz, Consumer<List<T>> consumer) {
        if (dto.sorts.isEmpty()) {
            dto.sortAsc("_shard_doc");
        }
        long total = 0;
        dto.from = null;
        dto.size = batch;
        dto.trackTotalHits = false;
        while (true) {
            SearchResultDto<T> page = searchPage(dto, clazz);
            if (page.hits.isEmpty()) {
                break;
            }
            consumer.accept(page.documents);
            total += page.documents.size();
            if (page.hits.size() < batch || page.searchAfterCursor == null) {
                break;
            }
            dto.searchAfter = page.searchAfterCursor;
        }
        return total;
    }

    // ==================== 聚合 ====================

    @Override
    public AggResult aggregate(SearchRequestDto dto, List<Agg> aggs) {
        long start = System.currentTimeMillis();
        try {
            SearchRequest req = toRequest(copyWithoutPaging(dto), buildAggMap(aggs));
            SearchResponse<Void> resp = client.search(req, Void.class);
            log.debug("聚合查询完成 index={} took={}ms", dto.index, System.currentTimeMillis() - start);
            return new AggResult(resp.aggregations());
        } catch (IOException e) {
            throw new EsException("聚合失败 index=" + dto.index, e);
        }
    }

    @Override
    public <T> Map.Entry<SearchResultDto<T>, AggResult> searchWithAggs(SearchRequestDto dto,
                                                                       List<Agg> aggs, Class<T> clazz) {
        long start = System.currentTimeMillis();
        try {
            SearchRequest req = toRequest(dto, buildAggMap(aggs));
            SearchResponse<T> resp = client.search(req, clazz);
            SearchResultDto<T> result = toResult(resp, dto, System.currentTimeMillis() - start);
            return new AbstractMap.SimpleEntry<>(result, new AggResult(resp.aggregations()));
        } catch (IOException e) {
            throw new EsException("查询+聚合失败 index=" + dto.index, e);
        }
    }

    @Override
    public void compositeEach(SearchRequestDto base, Agg compositeAgg, Consumer<AggResult.Bucket> handler) {
        Map<String, Object> after = null;
        while (true) {
            Agg round = compositeAgg.withAfter(after);
            AggResult result = aggregate(base, List.of(round));
            List<AggResult.Bucket> buckets = result.buckets(compositeAgg.name);
            if (buckets.isEmpty()) {
                return;
            }
            buckets.forEach(handler);
            after = result.afterKey(compositeAgg.name);
        }
    }

    @Override
    public <T> List<Hit<T>> raw(SearchRequest request, Class<T> clazz) {
        try {
            return client.search(request, clazz).hits().hits();
        } catch (IOException e) {
            throw new EsException("原生查询失败", e);
        }
    }

    // ==================== 内部 ====================

    private Map<String, Aggregation> buildAggMap(List<Agg> aggs) {
        Map<String, Aggregation> aggMap = new LinkedHashMap<>();
        aggs.forEach(a -> aggMap.put(a.name, a.build()));
        return aggMap;
    }

    private SearchRequestDto copyWithoutPaging(SearchRequestDto dto) {
        SearchRequestDto copy = new SearchRequestDto();
        copy.index = dto.index;
        copy.keyword = dto.keyword;
        copy.defaultFields.addAll(dto.defaultFields);
        copy.must.addAll(dto.must);
        copy.should.addAll(dto.should);
        copy.mustNot.addAll(dto.mustNot);
        copy.filter.addAll(dto.filter);
        copy.minimumShouldMatch = dto.minimumShouldMatch;
        copy.rawQuery = dto.rawQuery;
        copy.functionScore = dto.functionScore;
        copy.trackTotalHits = false;
        copy.timeout = dto.timeout;
        copy.size = 0;
        return copy;
    }

    SearchRequest toRequest(SearchRequestDto dto, Map<String, Aggregation> aggs) {
        Query query = QueryBuilderUtil.buildDto(dto);
        return SearchRequest.of(s -> {
            s.index(dto.index);
            s.query(query);
            if (dto.size != null) {
                s.size(dto.size);
            }
            if (dto.from != null) {
                s.from(dto.from);
            }
            if (dto.timeout != null) {
                s.timeout(dto.timeout);
            }
            s.trackTotalHits(t -> t.enabled(dto.trackTotalHits));
            if (!dto.sorts.isEmpty()) {
                List<SortOptions> sorts = new ArrayList<>();
                dto.sorts.forEach(item -> sorts.add(SortOptions.of(so -> so
                        .field(f -> f.field(item.field)
                                .order(item.desc ? SortOrder.Desc : SortOrder.Asc)))));
                s.sort(sorts);
            }
            if (dto.searchAfter != null && !dto.searchAfter.isEmpty()) {
                s.searchAfter(Condition.toFieldValues(dto.searchAfter));
            }
            if (dto.includes != null || dto.excludes != null) {
                s.source(src -> src.filter(f -> {
                    if (dto.includes != null) {
                        f.includes(dto.includes);
                    }
                    if (dto.excludes != null) {
                        f.excludes(dto.excludes);
                    }
                    return f;
                }));
            }
            if (!dto.highlightFields.isEmpty()) {
                Map<String, HighlightField> hfMap = new LinkedHashMap<>();
                dto.highlightFields.forEach(f -> hfMap.put(f, HighlightField.of(h -> {
                    if (dto.highlightPrefix != null) {
                        h.preTags(dto.highlightPrefix).postTags(dto.highlightSuffix);
                    }
                    return h;
                })));
                s.highlight(h -> h.fields(hfMap));
            }
            if (dto.collapse != null) {
                s.collapse(c -> {
                    c.field(dto.collapse.field);
                    if (dto.collapse.innerHitsSize != null) {
                        c.innerHits(h -> {
                            h.name(dto.collapse.innerHitsName == null
                                            ? "collapse_inner" : dto.collapse.innerHitsName)
                                    .size(dto.collapse.innerHitsSize);
                            if (dto.collapse.innerHitsIncludes != null) {
                                h.source(src -> src.filter(f -> {
                                    f.includes(dto.collapse.innerHitsIncludes);
                                    return f;
                                }));
                            }
                            return h;
                        });
                    }
                    return c;
                });
            }
            if (dto.preference != null) {
                s.preference(dto.preference);
            }
            if (dto.routing != null && !dto.routing.isEmpty()) {
                s.routing(String.join(",", dto.routing));
            }
            if (aggs != null && !aggs.isEmpty()) {
                s.aggregations(aggs);
            }
            return s;
        });
    }

    private <T> SearchResultDto<T> toResult(SearchResponse<T> resp, SearchRequestDto dto, long took) {
        SearchResultDto<T> result = new SearchResultDto<>();
        result.tookMillis = took;
        var hits = resp.hits();
        result.total = hits.total() == null ? 0 : hits.total().value();
        result.totalRelationGreaterThanOrEqual = hits.total() != null
                && hits.total().relation() == co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Gte;
        int size = dto.size == null ? 10 : dto.size;
        int from = dto.from == null ? 0 : dto.from;
        result.size = size;
        result.page = size == 0 ? 1 : from / size + 1;
        result.pages = size == 0 ? 0 : (int) Math.ceil((double) result.total / size);
        for (Hit<T> hit : hits.hits()) {
            HitWrapper<T> w = HitWrapper.from(hit);
            result.hits.add(w);
            if (w.source() != null) {
                result.documents.add(w.source());
            }
        }
        result.hasNext = result.totalRelationGreaterThanOrEqual
                || (long) from + result.hits.size() < result.total;
        result.hasPrev = from > 0;
        if (!result.hits.isEmpty()) {
            result.searchAfterCursor = result.hits.get(result.hits.size() - 1).sort();
        }
        return result;
    }
}
