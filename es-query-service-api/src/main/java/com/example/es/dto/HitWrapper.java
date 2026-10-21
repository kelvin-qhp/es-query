package com.example.es.dto;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.InnerHitsResult;

import java.util.List;
import java.util.Map;

/**
 * 单条命中：文档 + 元数据（id/score/sort 游标/高亮）。
 */
public class HitWrapper<T> {

    private String id;
    private String index;
    private double score;
    private T source;
    /** search_after 游标（与查询 sort 对应） */
    private List<Object> sort;
    /** 高亮片段：字段 -> 片段列表 */
    private Map<String, List<String>> highlight;
    /** collapse inner_hits：名称 -> 被折叠的文档（source 为 JsonData） */
    private Map<String, InnerHitsResult> innerHits;

    public static <T> HitWrapper<T> from(Hit<T> hit) {
        HitWrapper<T> w = new HitWrapper<>();
        w.id = hit.id();
        w.index = hit.index();
        w.score = hit.score() == null ? 0d : hit.score();
        w.source = hit.source();
        List<FieldValue> sortValues = hit.sort();
        if (sortValues != null && !sortValues.isEmpty()) {
            w.sort = sortValues.stream().map(HitWrapper::unwrap).toList();
        }
        if (hit.highlight() != null && !hit.highlight().isEmpty()) {
            w.highlight = hit.highlight();
        }
        if (hit.innerHits() != null && !hit.innerHits().isEmpty()) {
            w.innerHits = hit.innerHits();
        }
        return w;
    }

    private static Object unwrap(FieldValue v) {
        if (v.isString()) {
            return v.stringValue();
        }
        if (v.isLong()) {
            return v.longValue();
        }
        if (v.isDouble()) {
            return v.doubleValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return String.valueOf(v._get());
    }

    public String id() {
        return id;
    }

    public String index() {
        return index;
    }

    public double score() {
        return score;
    }

    public T source() {
        return source;
    }

    public List<Object> sort() {
        return sort;
    }

    public Map<String, List<String>> highlight() {
        return highlight;
    }

    /** collapse inner_hits；未开启 collapse 时为 null */
    public Map<String, InnerHitsResult> innerHits() {
        return innerHits;
    }

    /** 高亮片段合并为一行文本 */
    public String highlightText(String field) {
        if (highlight == null) {
            return null;
        }
        List<String> fragments = highlight.get(field);
        return fragments == null || fragments.isEmpty() ? null : String.join(" ... ", fragments);
    }

    @Override
    public String toString() {
        return "Hit{id=" + id + ", score=" + score + ", source=" + source + '}';
    }
}
