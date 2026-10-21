package com.example.es.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 查询请求 DTO：bool 组合 + 分页/排序 + 全局参数。
 *
 * <pre>
 * SearchRequestDto dto = new SearchRequestDto("products")
 *         .keyword("手机", "title^3", "summary")
 *         .must(Condition.term("status", 1))
 *         .must(Condition.range("price", 0, 5000))
 *         .mustNot(Condition.term("deleted", true))
 *         .should(2, Condition.match("brand", "huawei"), Condition.match("brand", "xiaomi"))
 *         .sortDesc("create_time")
 *         .page(1, 20);
 * </pre>
 */
public class SearchRequestDto {

    /** 索引名，支持别名与通配符（如 logs-*） */
    public String index;

    /** 简易全文：multi_match，配合 defaultFields */
    public String keyword;
    public List<String> defaultFields = new ArrayList<>();

    public final List<Condition> must = new ArrayList<>();
    public final List<Condition> should = new ArrayList<>();
    public final List<Condition> mustNot = new ArrayList<>();
    /** 放入 filter 上下文（不算分，可缓存），term/terms/range/exists 建议走这里 */
    public final List<Condition> filter = new ArrayList<>();
    public Integer minimumShouldMatch;
    /** 原生 Query 逃生舱：非空时忽略 keyword/must/should/mustNot/filter 组合 */
    public co.elastic.clients.elasticsearch._types.query_dsl.Query rawQuery;
    /** function_score 打分定制：非空时把最终 query 包一层 function_score */
    public FunctionScore functionScore;
    /** collapse 折叠：同一字段值只保留一条命中 */
    public Collapse collapse;

    public Integer from;
    public Integer size;
    /** 深分页游标（配合 sort，size 即每页条数） */
    public List<Object> searchAfter;
    public boolean trackTotalHits = true;
    /** 超时，如 "5s" */
    public String timeout;
    public String preference;
    public List<String> routing;
    /** 返回文档字段 */
    public List<String> includes;
    public List<String> excludes;
    /** 高亮字段 */
    public List<String> highlightFields = new ArrayList<>();
    public String highlightPrefix;
    public String highlightSuffix;

    /** 排序项：field + desc */
    public static class SortItem {
        public final String field;
        public final boolean desc;
        public SortItem(String field, boolean desc) {
            this.field = field;
            this.desc = desc;
        }
    }

    public final List<SortItem> sorts = new ArrayList<>();

    public SearchRequestDto() {
    }

    public SearchRequestDto(String index) {
        this.index = index;
    }

    public SearchRequestDto index(String index) {
        this.index = index;
        return this;
    }

    /** 全文关键词，fields 可带权重（"title^3"）；不传则用 index 默认字段 */
    public SearchRequestDto keyword(String keyword, String... fields) {
        this.keyword = keyword;
        if (fields != null && fields.length > 0) {
            this.defaultFields.addAll(Arrays.asList(fields));
        }
        return this;
    }

    public SearchRequestDto must(Condition... conds) {
        must.addAll(Arrays.asList(conds));
        return this;
    }

    public SearchRequestDto mustNot(Condition... conds) {
        mustNot.addAll(Arrays.asList(conds));
        return this;
    }

    /** filter 上下文：精确/范围/存在类条件建议用它，不算分且可利用节点缓存 */
    public SearchRequestDto filter(Condition... conds) {
        filter.addAll(Arrays.asList(conds));
        return this;
    }

    public SearchRequestDto should(Condition... conds) {
        should.addAll(Arrays.asList(conds));
        return this;
    }

    /** 至少命中 minimumShouldMatch 个 should 条件 */
    public SearchRequestDto should(int minimumShouldMatch, Condition... conds) {
        should.addAll(Arrays.asList(conds));
        this.minimumShouldMatch = minimumShouldMatch;
        return this;
    }

    /** function_score：衰减/字段值/脚本等自定义打分，非空时最终 query 包一层 function_score */
    public SearchRequestDto functionScore(FunctionScore functionScore) {
        this.functionScore = functionScore;
        return this;
    }

    /** collapse 折叠：字段须为 keyword/数值，如 Collapse.on("user_id").innerHits(3) */
    public SearchRequestDto collapse(Collapse collapse) {
        this.collapse = collapse;
        return this;
    }

    public SearchRequestDto collapse(String field) {
        this.collapse = Collapse.on(field);
        return this;
    }

    public SearchRequestDto page(int pageNo, int pageSize) {
        this.from = Math.max(0, (pageNo - 1) * pageSize);
        this.size = pageSize;
        return this;
    }

    public SearchRequestDto from(int from) {
        this.from = from;
        return this;
    }

    public SearchRequestDto size(int size) {
        this.size = size;
        return this;
    }

    public SearchRequestDto searchAfter(Object... cursors) {
        this.searchAfter = Arrays.asList(cursors);
        return this;
    }

    public SearchRequestDto sortAsc(String field) {
        sorts.add(new SortItem(field, false));
        return this;
    }

    public SearchRequestDto sortDesc(String field) {
        sorts.add(new SortItem(field, true));
        return this;
    }

    /** 默认按相关性得分排序 */
    public SearchRequestDto sortByScore() {
        sorts.add(new SortItem("_score", true));
        return this;
    }

    public SearchRequestDto trackTotalHits(boolean track) {
        this.trackTotalHits = track;
        return this;
    }

    public SearchRequestDto timeout(String timeout) {
        this.timeout = timeout;
        return this;
    }

    public SearchRequestDto preference(String preference) {
        this.preference = preference;
        return this;
    }

    public SearchRequestDto routing(String... routing) {
        this.routing = Arrays.asList(routing);
        return this;
    }

    /** 只返回指定字段（_source 过滤），减少传输 */
    public SearchRequestDto fetchFields(String... includes) {
        this.includes = Arrays.asList(includes);
        return this;
    }

    public SearchRequestDto excludeFields(String... excludes) {
        this.excludes = Arrays.asList(excludes);
        return this;
    }

    public SearchRequestDto highlight(String... fields) {
        this.highlightFields.addAll(Arrays.asList(fields));
        return this;
    }

    public SearchRequestDto highlightTags(String prefix, String suffix) {
        this.highlightPrefix = prefix;
        this.highlightSuffix = suffix;
        return this;
    }
}
