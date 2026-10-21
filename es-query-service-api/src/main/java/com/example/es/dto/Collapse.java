package com.example.es.dto;

import java.util.Arrays;
import java.util.List;

/**
 * collapse 折叠：同一字段值只保留一条命中（默认保留排序首位的那条），
 * 配合 inner_hits 可取出被折叠掉的其余文档。
 *
 * <pre>
 * new SearchRequestDto("orders")
 *         .collapse(Collapse.on("user_id").innerHits(3))
 *         .sortDesc("amount");
 * </pre>
 */
public class Collapse {

    /** 折叠字段：仅支持 keyword 或数值类型，text 字段请用其 keyword 子字段（如 title.keyword） */
    public String field;
    /** inner_hits 条数；null 表示不返回被折叠的文档 */
    public Integer innerHitsSize;
    /** inner_hits 名称，默认 collapse_inner */
    public String innerHitsName;
    /** inner_hits 的 _source 字段过滤 */
    public List<String> innerHitsIncludes;

    public Collapse() {
    }

    public Collapse(String field) {
        this.field = field;
    }

    public static Collapse on(String field) {
        return new Collapse(field);
    }

    /** 返回每个折叠值下被隐藏的文档（默认名 collapse_inner） */
    public Collapse innerHits(int size) {
        this.innerHitsSize = size;
        return this;
    }

    public Collapse innerHits(String name, int size) {
        this.innerHitsName = name;
        this.innerHitsSize = size;
        return this;
    }

    public Collapse innerHitsFields(String... includes) {
        this.innerHitsIncludes = Arrays.asList(includes);
        return this;
    }
}
