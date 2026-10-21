package com.example.es.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询结果。
 */
public class SearchResultDto<T> {

    public long total;
    /** 当 trackTotalHits 关闭或超过 10000 时，total 可能不精确（ES 返回 gte） */
    public boolean totalRelationGreaterThanOrEqual;
    public List<HitWrapper<T>> hits = new ArrayList<>();
    public List<T> documents = new ArrayList<>();
    public int page;
    public int size;
    public int pages;
    public boolean hasNext;
    public boolean hasPrev;
    /** search_after 游标（最后一条 hit 的 sort 值），传给下一次查询 */
    public List<Object> searchAfterCursor;
    public long tookMillis;

    public List<T> results() {
        return documents;
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    @Override
    public String toString() {
        return "SearchResultDto{total=" + total + ", page=" + page + ", size=" + size
                + ", hits=" + hits.size() + ", hasNext=" + hasNext + '}';
    }
}
