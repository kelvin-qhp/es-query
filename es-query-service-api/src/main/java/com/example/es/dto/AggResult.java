package com.example.es.dto;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Buckets;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.DoubleTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.HistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.MultiBucketBase;
import co.elastic.clients.elasticsearch._types.aggregations.RangeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.TDigestPercentilesAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.HdrPercentilesAggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合结果解析器：把官方 Aggregate 树转成易用的 Bucket/Stats 结构（ES java client 9.x）。
 */
public class AggResult {

    public static class Bucket {
        /** 桶 key：terms 为字段值，直方图/日期直方图为数值或格式化日期 */
        public String key;
        public long docCount;
        /** 子聚合结果（可继续读 avg / terms 等） */
        public Map<String, Object> subAggs = new LinkedHashMap<>();
        /** composite 桶的多维 key */
        public Map<String, String> compositeKey;

        @Override
        public String toString() {
            return "Bucket{key=" + key + ", docCount=" + docCount + ", subAggs=" + subAggs + '}';
        }
    }

    public static class Stats {
        public long count;
        public double min;
        public double max;
        public double avg;
        public double sum;

        @Override
        public String toString() {
            return "Stats{count=" + count + ", min=" + min + ", max=" + max + ", avg=" + avg + ", sum=" + sum + '}';
        }
    }

    private final Map<String, Aggregate> raw;

    public AggResult(Map<String, Aggregate> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    public Map<String, Aggregate> raw() {
        return raw;
    }

    /** terms / range / dateRange / histogram / dateHistogram / composite / filter 等桶聚合统一入口 */
    public List<Bucket> buckets(String name) {
        Aggregate agg = require(name);
        List<Bucket> out = new ArrayList<>();
        if (agg.isSterms()) {
            forEach(agg.sterms().buckets(), b ->
                    out.add(of(fv(b.key()), b.docCount(), b.aggregations(), null)));
        } else if (agg.isLterms()) {
            forEach(agg.lterms().buckets(), b -> {
                String key = b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key());
                out.add(of(key, b.docCount(), b.aggregations(), null));
            });
        } else if (agg.isDterms()) {
            forEach(agg.dterms().buckets(), b -> {
                String key = b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key());
                out.add(of(key, b.docCount(), b.aggregations(), null));
            });
        } else if (agg.isDateHistogram()) {
            forEach(agg.dateHistogram().buckets(), b -> {
                String key = b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key());
                out.add(of(key, b.docCount(), b.aggregations(), null));
            });
        } else if (agg.isHistogram()) {
            forEach(agg.histogram().buckets(), b -> {
                String key = b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key());
                out.add(of(key, b.docCount(), b.aggregations(), null));
            });
        } else if (agg.isRange() || agg.isDateRange()) {
            forEach(agg.range().buckets(), b ->
                    out.add(of(rangeKey(b), b.docCount(), b.aggregations(), null)));
        } else if (agg.isComposite()) {
            CompositeAggregate ca = agg.composite();
            for (CompositeBucket b : array(ca.buckets())) {
                Bucket bucket = new Bucket();
                bucket.compositeKey = new LinkedHashMap<>();
                b.key().forEach((k, v) -> bucket.compositeKey.put(k, fv(v)));
                bucket.key = String.valueOf(bucket.compositeKey);
                bucket.docCount = b.docCount();
                b.aggregations().forEach((k, v) -> bucket.subAggs.put(k, read(v)));
                out.add(bucket);
            }
        } else if (agg.isFilter()) {
            var single = agg.filter();
            out.add(of("_filter", single.docCount(), single.aggregations(), null));
        } else if (agg.isNested() || agg.isGlobal() || agg.isReverseNested()) {
            var single = agg.isNested() ? agg.nested() : agg.isGlobal() ? agg.global() : agg.reverseNested();
            out.add(of("_bucket", single.docCount(), single.aggregations(), null));
        } else {
            throw new IllegalStateException("聚合 " + name + " 不是桶聚合，请用 avg()/termsMap() 等指标读取；实际类型: "
                    + agg._kind());
        }
        return out;
    }

    /** composite 续传游标 */
    public Map<String, Object> afterKey(String compositeName) {
        Aggregate agg = require(compositeName);
        if (!agg.isComposite()) {
            throw new IllegalStateException(compositeName + " 不是 composite 聚合");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        agg.composite().afterKey().forEach((k, v) -> out.put(k, v._get()));
        return out;
    }

    /** 单个 Aggregate -> Java 值 */
    public static Object read(Aggregate agg) {
        if (agg == null) {
            return null;
        }
        if (agg.isAvg()) {
            return agg.avg().value();
        }
        if (agg.isSum()) {
            return agg.sum().value();
        }
        if (agg.isMax()) {
            return agg.max().value();
        }
        if (agg.isMin()) {
            return agg.min().value();
        }
        if (agg.isValueCount()) {
            return agg.valueCount().value();
        }
        if (agg.isCardinality()) {
            return agg.cardinality().value();
        }
        if (agg.isStats()) {
            return toStats(agg.stats().count(), agg.stats().min(), agg.stats().max(),
                    agg.stats().avg(), agg.stats().sum());
        }
        if (agg.isExtendedStats()) {
            var s = agg.extendedStats();
            return toStats(s.count(), s.min(), s.max(), s.avg(), s.sum());
        }
        if (agg.isHdrPercentiles()) {
            return percentiles(agg.hdrPercentiles().values());
        }
        if (agg.isTdigestPercentiles()) {
            return percentiles(agg.tdigestPercentiles().values());
        }
        if (agg.isTopHits()) {
            return agg.topHits().hits().hits();
        }
        // 桶聚合整体转成 Bucket 列表
        Map<String, Aggregate> wrapper = new LinkedHashMap<>();
        wrapper.put("_", agg);
        return new AggResult(wrapper).buckets("_");
    }

    private static Stats toStats(long count, Double min, Double max, Double avg, double sum) {
        Stats st = new Stats();
        st.count = count;
        st.min = min == null ? Double.NaN : min;
        st.max = max == null ? Double.NaN : max;
        st.avg = avg == null ? Double.NaN : avg;
        st.sum = sum;
        return st;
    }

    private static Map<String, Double> percentiles(co.elastic.clients.elasticsearch._types.aggregations.Percentiles p) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (p.isKeyed()) {
            p.keyed().forEach((k, v) -> out.put(k, v == null ? null : Double.valueOf(v)));
        } else {
            p.array().forEach(item -> out.put(item.key(), item.value()));
        }
        return out;
    }

    // ---------- 便捷读取 ----------

    public double avg(String name) {
        return asDouble(read(require(name)));
    }

    public double sum(String name) {
        return asDouble(read(require(name)));
    }

    public double min(String name) {
        return asDouble(read(require(name)));
    }

    public double max(String name) {
        return asDouble(read(require(name)));
    }

    public long count(String name) {
        Object v = read(require(name));
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("聚合 " + name + " 不是计数类指标: " + v);
    }

    public Stats stats(String name) {
        Object v = read(require(name));
        if (v instanceof Stats s) {
            return s;
        }
        throw new IllegalStateException("聚合 " + name + " 不是 stats");
    }

    public List<Bucket> terms(String name) {
        return buckets(name);
    }

    /** terms 聚合的 key -> doc_count 有序映射 */
    public Map<String, Long> termsMap(String name) {
        Map<String, Long> m = new LinkedHashMap<>();
        buckets(name).forEach(b -> m.put(b.key, b.docCount));
        return m;
    }

    private Aggregate require(String name) {
        Aggregate agg = raw.get(name);
        if (agg == null) {
            throw new IllegalArgumentException("聚合结果不存在: " + name + "，现有: " + raw.keySet());
        }
        return agg;
    }

    // ---------- 内部工具 ----------

    private static String fv(FieldValue v) {
        if (v == null) {
            return null;
        }
        if (v.isString()) {
            return v.stringValue();
        }
        Object o = v._get();
        return o == null ? null : String.valueOf(o);
    }

    private static String rangeKey(RangeBucket b) {
        if (b.key() != null) {
            return b.key();
        }
        String from = b.from() == null ? (b.fromAsString() == null ? "*" : b.fromAsString()) : String.valueOf(b.from());
        String to = b.to() == null ? (b.toAsString() == null ? "*" : b.toAsString()) : String.valueOf(b.to());
        return from + "-" + to;
    }

    private static <T> List<T> array(Buckets<T> buckets) {
        if (buckets.isKeyed()) {
            return new ArrayList<>(buckets.keyed().values());
        }
        return buckets.array();
    }

    private static <T extends MultiBucketBase> void forEach(Buckets<T> buckets, java.util.function.Consumer<T> consumer) {
        if (buckets.isKeyed()) {
            buckets.keyed().values().forEach(consumer);
        } else {
            buckets.array().forEach(consumer);
        }
    }

    private static Bucket of(String key, long docCount, Map<String, Aggregate> subs, Map<?, ?> compositeKey) {
        Bucket b = new Bucket();
        b.key = key;
        b.docCount = docCount;
        if (compositeKey != null) {
            b.compositeKey = new LinkedHashMap<>();
            compositeKey.forEach((k, v) -> b.compositeKey.put(String.valueOf(k), String.valueOf(v)));
        }
        if (subs != null) {
            subs.forEach((k, v) -> b.subAggs.put(k, read(v)));
        }
        return b;
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException("聚合结果不是数值: " + v);
    }
}
