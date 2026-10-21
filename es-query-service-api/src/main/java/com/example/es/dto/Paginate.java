package com.example.es.dto;

import java.util.Map;

/**
 * search_after / composite 游标分页包装。
 * 用法：第一批 after=null；取回 after 传入下一次查询，直到 buckets/hits 为空。
 */
public class Paginate {

    /** 上一页末尾游标（search_after 的 sort values 或 composite 的 afterKey） */
    public Map<String, Object> afterKey;
    /** 本次返回的桶/文档 */
    public java.util.List<?> data;
    /** search_after 模式下的 sort values 游标（下一批用） */
    public java.util.List<Object> cursor;

    public boolean hasMore() {
        return data != null && !data.isEmpty();
    }

    public static Paginate of(java.util.List<?> data, Map<String, Object> afterKey) {
        Paginate p = new Paginate();
        p.data = data;
        p.afterKey = afterKey;
        return p;
    }

    public static Paginate of(java.util.List<?> data, java.util.List<Object> cursor) {
        Paginate p = new Paginate();
        p.data = data;
        p.cursor = cursor;
        return p;
    }
}
