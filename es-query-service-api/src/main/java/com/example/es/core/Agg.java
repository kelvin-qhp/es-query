package com.example.es.core;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.example.es.dto.Condition;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation.Builder;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.DateRangeExpression;
import co.elastic.clients.elasticsearch._types.aggregations.FieldDateMath;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.util.NamedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 聚合构造器（fluent），转换为官方客户端 Aggregation（ES java client 9.x）。
 *
 * <pre>
 * Agg terms = Agg.terms("by_brand", "brand").size(20).orderCountDesc()
 *         .sub(Agg.avg("avg_price", "price"), Agg.topHits("samples", 3));
 * Agg trend = Agg.dateHistogram("trend", "create_time", CalendarInterval.Day, "yyyy-MM-dd")
 *         .sub(Agg.cardinality("uv", "user_id"));
 * </pre>
 */
public class Agg {

    public enum AggType {
        TERMS, FILTER, RANGE, DATE_RANGE, DATE_HISTOGRAM, HISTOGRAM, NESTED, COMP,
        AVG, SUM, MIN, MAX, VALUE_COUNT, CARDINALITY, STATS, EXTENDED_STATS, PERCENTILES, TOP_HITS
    }

    public final String name;
    public final AggType type;
    public String field;
    public Integer size;
    public String interval;
    public String format;
    public boolean countDesc;
    public Query filterQuery;
    public String path;
    public final List<Agg> subs = new ArrayList<>();
    public final List<AggregationRange> ranges = new ArrayList<>();
    public final List<DateRangeExpression> dateRanges = new ArrayList<>();
    public final List<Map<String, CompositeAggregationSource>> compSources = new ArrayList<>();
    public Map<String, ?> after;
    public String rawJson;
    public Consumer<Builder> customizer;

    public Agg(String name, AggType type) {
        this.name = name;
        this.type = type;
    }

    // ---------- 静态工厂 ----------

    public static Agg terms(String name, String field) {
        return new Agg(name, AggType.TERMS).field(field);
    }

    public static Agg avg(String name, String field) {
        return new Agg(name, AggType.AVG).field(field);
    }

    public static Agg sum(String name, String field) {
        return new Agg(name, AggType.SUM).field(field);
    }

    public static Agg min(String name, String field) {
        return new Agg(name, AggType.MIN).field(field);
    }

    public static Agg max(String name, String field) {
        return new Agg(name, AggType.MAX).field(field);
    }

    public static Agg valueCount(String name, String field) {
        return new Agg(name, AggType.VALUE_COUNT).field(field);
    }

    /** 近似去重计数 */
    public static Agg cardinality(String name, String field) {
        return new Agg(name, AggType.CARDINALITY).field(field);
    }

    public static Agg stats(String name, String field) {
        return new Agg(name, AggType.STATS).field(field);
    }

    public static Agg extendedStats(String name, String field) {
        return new Agg(name, AggType.EXTENDED_STATS).field(field);
    }

    public static Agg percentiles(String name, String field) {
        return new Agg(name, AggType.PERCENTILES).field(field);
    }

    /** 数值直方图 */
    public static Agg histogram(String name, String field, double interval) {
        Agg a = new Agg(name, AggType.HISTOGRAM).field(field);
        a.interval = String.valueOf(interval);
        return a;
    }

    /** 日历日期直方图（Day/Month/...） */
    public static Agg dateHistogram(String name, String field, CalendarInterval interval, String format) {
        Agg a = new Agg(name, AggType.DATE_HISTOGRAM).field(field);
        a.interval = interval.jsonValue();
        a.format = format;
        return a;
    }

    /** 固定间隔日期直方图，interval 如 "30m"、"2h" */
    public static Agg dateHistogramFixed(String name, String field, String interval, String format) {
        Agg a = new Agg(name, AggType.DATE_HISTOGRAM).field(field);
        a.interval = interval;
        a.format = format;
        return a;
    }

    /** 数值区间聚合：按升序切分点生成 [e0,e1) ... [last,+) */
    public static Agg range(String name, String field, double... ascendingEdges) {
        Agg a = new Agg(name, AggType.RANGE).field(field);
        for (int i = 0; i < ascendingEdges.length - 1; i++) {
            final int i1 = i;
            a.ranges.add(AggregationRange.of(b -> b.from(ascendingEdges[i1]).to(ascendingEdges[i1 + 1])));
        }
        double last = ascendingEdges[ascendingEdges.length - 1];
        a.ranges.add(AggregationRange.of(b -> b.from(last)));
        return a;
    }

    /** 自定义数值区间：key -> [from, to)（to 缺省表示 +∞） */
    public static Agg range(String name, String field, Map<String, double[]> rangesByKey) {
        Agg a = new Agg(name, AggType.RANGE).field(field);
        rangesByKey.forEach((k, v) -> {
            if (v.length >= 2) {
                a.ranges.add(AggregationRange.of(b -> b.key(k).from(v[0]).to(v[1])));
            } else {
                a.ranges.add(AggregationRange.of(b -> b.key(k).from(v[0])));
            }
        });
        return a;
    }

    /** 日期区间：key -> [from, to)（表达式如 "now-7d"、"2026-01-01"） */
    public static Agg dateRange(String name, String field, String format, Map<String, String[]> rangesByKey) {
        Agg a = new Agg(name, AggType.DATE_RANGE).field(field);
        a.format = format;
        rangesByKey.forEach((k, v) -> a.dateRanges.add(DateRangeExpression.of(b -> {
            b.key(k);
            if (v.length > 0 && v[0] != null) {
                b.from(f -> f.expr(v[0]));
            }
            if (v.length > 1 && v[1] != null) {
                b.to(f -> f.expr(v[1]));
            }
            return b;
        })));
        return a;
    }

    /** filter 桶：只统计命中该查询的文档 */
    public static Agg filter(String name, Query q, Agg... subs) {
        Agg a = new Agg(name, AggType.FILTER);
        a.filterQuery = q;
        a.sub(subs);
        return a;
    }

    /** nested 聚合，path 为 nested 字段路径 */
    public static Agg nested(String name, String path) {
        Agg a = new Agg(name, AggType.NESTED);
        a.path = path;
        return a;
    }

    /** 命中样本 */
    public static Agg topHits(String name, int size) {
        return new Agg(name, AggType.TOP_HITS).size(size);
    }

    /** composite 游标分页聚合；after 传上一页末桶的 afterKey */
    public static Agg composite(String name, Map<String, CompositeAggregationSource> sources, Map<String, ?> after) {
        Agg a = new Agg(name, AggType.COMP);
        a.compSources.add(new LinkedHashMap<>(sources));
        a.after = after;
        return a;
    }

    public static Map<String, CompositeAggregationSource> sources(String name, CompositeAggregationSource s) {
        Map<String, CompositeAggregationSource> m = new LinkedHashMap<>();
        m.put(name, s);
        return m;
    }

    public static CompositeAggregationSource termsSource(String field) {
        return CompositeAggregationSource.of(c -> c.terms(t -> t.field(field)));
    }

    public static CompositeAggregationSource histSource(String field, double interval) {
        return CompositeAggregationSource.of(c -> c.histogram(h -> h.field(field).interval(interval)));
    }

    /** 原生聚合 JSON 兜底 */
    public static Agg raw(String name, String aggJson) {
        Agg a = new Agg(name, AggType.TERMS);
        a.rawJson = aggJson;
        return a;
    }

    // ---------- 链式参数 ----------

    public Agg field(String field) {
        this.field = field;
        return this;
    }

    public Agg size(Integer size) {
        this.size = size;
        return this;
    }

    public Agg orderCountDesc() {
        this.countDesc = true;
        return this;
    }

    public Agg sub(Agg... subAggs) {
        this.subs.addAll(Arrays.asList(subAggs));
        return this;
    }

    /** 逃生舱：直接操作官方 Aggregation.Builder */
    public Agg customize(Consumer<Builder> c) {
        this.customizer = c;
        return this;
    }

    /** composite 续传游标（返回副本，不修改原对象） */
    public Agg withAfter(Map<String, ?> afterKey) {
        Agg copy = new Agg(name, type);
        copy.field = field;
        copy.size = size;
        copy.interval = interval;
        copy.format = format;
        copy.countDesc = countDesc;
        copy.filterQuery = filterQuery;
        copy.path = path;
        copy.subs.addAll(subs);
        copy.ranges.addAll(ranges);
        copy.dateRanges.addAll(dateRanges);
        copy.compSources.addAll(compSources);
        copy.rawJson = rawJson;
        copy.customizer = customizer;
        copy.after = afterKey;
        return copy;
    }

    /** name -> Aggregation，便于放入 SearchRequest.aggregations */
    public Map<String, Aggregation> toMap() {
        Map<String, Aggregation> m = new LinkedHashMap<>();
        m.put(name, build());
        return m;
    }

    // ---------- 构建 ----------

    public Aggregation build() {
        if (rawJson != null) {
            return Aggregation.of(a -> a.withJson(new java.io.StringReader(rawJson)));
        }
        if (customizer != null) {
            return Aggregation.of(a -> {
                customizer.accept(a);
                return a;
            });
        }
        Map<String, Aggregation> subMap = new LinkedHashMap<>();
        subs.forEach(s -> subMap.put(s.name, s.build()));

        return Aggregation.of(a -> {
            apply(a);
            if (!subMap.isEmpty()) {
                a.aggregations(subMap);
            }
            return a;
        });
    }

    private void apply(Builder a) {
        switch (type) {
            case TERMS -> a.terms(t -> {
                t.field(field);
                if (size != null) {
                    t.size(size);
                }
                if (countDesc) {
                    t.order(NamedValue.of("_count", SortOrder.Desc));
                }
                return t;
            });
            case AVG -> a.avg(m -> m.field(field));
            case SUM -> a.sum(m -> m.field(field));
            case MIN -> a.min(m -> m.field(field));
            case MAX -> a.max(m -> m.field(field));
            case VALUE_COUNT -> a.valueCount(m -> m.field(field));
            case CARDINALITY -> a.cardinality(m -> m.field(field));
            case STATS -> a.stats(m -> m.field(field));
            case EXTENDED_STATS -> a.extendedStats(m -> m.field(field));
            case PERCENTILES -> a.percentiles(m -> m.field(field));
            case HISTOGRAM -> a.histogram(h -> {
                h.field(field);
                if (interval != null) {
                    h.interval(Double.parseDouble(interval));
                }
                return h;
            });
            case DATE_HISTOGRAM -> a.dateHistogram(dh -> {
                dh.field(field);
                if (interval != null) {
                    switch (interval) {
                        case "s", "Second" -> dh.calendarInterval(CalendarInterval.Second);
                        case "m", "Minute" -> dh.calendarInterval(CalendarInterval.Minute);
                        case "h", "Hour" -> dh.calendarInterval(CalendarInterval.Hour);
                        case "d", "Day" -> dh.calendarInterval(CalendarInterval.Day);
                        case "w", "Week" -> dh.calendarInterval(CalendarInterval.Week);
                        case "M", "Month" -> dh.calendarInterval(CalendarInterval.Month);
                        case "q", "Quarter" -> dh.calendarInterval(CalendarInterval.Quarter);
                        case "y", "Year" -> dh.calendarInterval(CalendarInterval.Year);
                        // 非日历单位（"30m"、"2h"）走 fixed_interval
                        default -> dh.fixedInterval(f -> f.time(interval));
                    }
                }
                if (format != null) {
                    dh.format(format);
                }
                return dh;
            });
            case RANGE -> a.range(r -> r.field(field).ranges(ranges));
            case DATE_RANGE -> a.dateRange(dr -> {
                dr.field(field).ranges(dateRanges);
                if (format != null) {
                    dr.format(format);
                }
                return dr;
            });
            case FILTER -> a.filter(filterQuery);
            case NESTED -> a.nested(n -> n.path(path));
            case TOP_HITS -> a.topHits(th -> th.size(size == null ? 3 : size));
            case COMP -> a.composite(c -> {
                c.sources(compSources);
                if (after != null) {
                    Map<String, FieldValue> fv = new LinkedHashMap<>();
                    after.forEach((k, v) -> fv.put(k, Condition.toFieldValue(v)));
                    c.after(fv);
                }
                return c;
            });
        }
    }
}
