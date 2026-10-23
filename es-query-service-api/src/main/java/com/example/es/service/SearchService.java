package com.example.es.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.example.es.core.Agg;
import com.example.es.dto.AggResult;
import com.example.es.dto.SearchRequestDto;
import com.example.es.dto.SearchResultDto;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Elasticsearch 9.x 查询 & 聚合服务封装。
 */
public interface SearchService {

    // ==================== 查询 ====================

    /** 执行 DTO 查询，返回文档列表 */
    <T> List<T> search(SearchRequestDto dto, Class<T> clazz);

    /** 执行 DTO 查询，返回官方 SearchResponse（文档为 ObjectNode），无需指定反序列化类型 */
    SearchResponse<ObjectNode> search(SearchRequestDto dto);

    /** 执行 DTO 查询，返回完整结果（含游标、高亮、总数） */
    <T> SearchResultDto<T> searchPage(SearchRequestDto dto, Class<T> clazz);

    /** 分页查询（pageNo 从 1 开始） */
    <T> SearchResultDto<T> paginate(String index, Function<Query.Builder, ObjectBuilder<Query>> queryBuilder,
                                    int pageNo, int pageSize, Class<T> clazz);

    /** search_after 深分页：首次 dto.searchAfter 传 null，之后传上一次结果的 searchAfterCursor */
    <T> SearchResultDto<T> searchAfter(SearchRequestDto dto, Class<T> clazz);

    /** 只取总数（trackTotalHits 强制开启） */
    long count(SearchRequestDto dto);

    /**
     * 遍历全部命中（search_after 模式），每批交给 consumer，避免一次性载入内存。
     * 需要 dto 中存在确定性排序（实现会自动补 _shard_doc）。
     */
    <T> long searchAll(SearchRequestDto dto, int batch, Class<T> clazz, Consumer<List<T>> consumer);

    // ==================== 聚合 ====================

    /** 纯聚合查询（size=0），返回解析后的聚合结果 */
    AggResult aggregate(SearchRequestDto dto, List<Agg> aggs);

    /** 查询 + 聚合一次返回 */
    <T> Map.Entry<SearchResultDto<T>, AggResult> searchWithAggs(SearchRequestDto dto, List<Agg> aggs, Class<T> clazz);

    /** composite 聚合游标遍历：每轮把桶交给 handler，afterKey 自动续传，直到取空 */
    void compositeEach(SearchRequestDto base, Agg compositeAgg, Consumer<AggResult.Bucket> handler);

    /** 原生 SearchRequest 逃生舱 */
    <T> List<Hit<T>> raw(SearchRequest request, Class<T> clazz);
}
