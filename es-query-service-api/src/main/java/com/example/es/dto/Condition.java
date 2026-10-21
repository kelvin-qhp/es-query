package com.example.es.dto;

import co.elastic.clients.elasticsearch._types.FieldValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单条查询条件。通过静态工厂创建，可挂到 SearchRequestDto 的 must/should/mustNot 上。
 */
public class Condition {

    public enum Type {
        TERM, TERMS, MATCH, MATCH_PHRASE, MATCH_BOOL_PREFIX, PREFIX, WILDCARD, REGEXP,
        RANGE, EXISTS, IDS, NESTED, RAW
    }

    public Type type;
    public String field;

    /** TERM/MATCH/PREFIX 等单值条件的值 */
    public Object value;
    /** TERMS 条件的多值 */
    public List<Object> values;

    /** RANGE 边界：Number 或 String（日期） */
    public Object from;
    public Object to;
    public boolean includeFrom = true;
    public boolean includeTo = true;
    /** RANGE 边界按日期解析（配合 String 边界如 "now-1d"、"2026-01-01"） */
    public boolean date;

    /** MATCH 系列的算子：AND / OR */
    public String operator = "AND";
    public String fuzziness;

    /** EXISTS 取反（字段不存在） */
    public boolean missing;

    /** NESTED：路径 */
    public String path;
    /** NESTED：内层条件 */
    public List<Condition> inner;

    /** RAW：原生查询 JSON 或聚合 DSL 片段 */
    public String rawJson;

    public static Condition term(String field, Object value) {
        Condition c = new Condition();
        c.type = Type.TERM;
        c.field = field;
        c.value = value;
        return c;
    }

    public static Condition terms(String field, Object... values) {
        return terms(field, Arrays.asList(values));
    }

    public static Condition terms(String field, Collection<?> values) {
        Condition c = new Condition();
        c.type = Type.TERMS;
        c.field = field;
        c.values = new ArrayList<>(values);
        return c;
    }

    public static Condition match(String field, Object text) {
        Condition c = new Condition();
        c.type = Type.MATCH;
        c.field = field;
        c.value = text;
        return c;
    }

    public static Condition matchPhrase(String field, Object text) {
        Condition c = new Condition();
        c.type = Type.MATCH_PHRASE;
        c.field = field;
        c.value = text;
        return c;
    }

    public static Condition matchBoolPrefix(String field, Object text) {
        Condition c = new Condition();
        c.type = Type.MATCH_BOOL_PREFIX;
        c.field = field;
        c.value = text;
        return c;
    }

    /** 为 match 系列设置算子（AND/OR），返回自身便于链式调用 */
    public Condition operator(String op) {
        this.operator = op;
        return this;
    }

    public Condition fuzziness(String fuzziness) {
        this.fuzziness = fuzziness;
        return this;
    }

    public static Condition prefix(String field, String prefix) {
        Condition c = new Condition();
        c.type = Type.PREFIX;
        c.field = field;
        c.value = prefix;
        return c;
    }

    public static Condition wildcard(String field, String pattern) {
        Condition c = new Condition();
        c.type = Type.WILDCARD;
        c.field = field;
        c.value = pattern;
        return c;
    }

    public static Condition regexp(String field, String pattern) {
        Condition c = new Condition();
        c.type = Type.REGEXP;
        c.field = field;
        c.value = pattern;
        return c;
    }

    /** 闭区间 [from, to]；参数为 Number 或日期 String */
    public static Condition range(String field, Object from, Object to) {
        Condition c = new Condition();
        c.type = Type.RANGE;
        c.field = field;
        c.from = from;
        c.to = to;
        return c;
    }

    public static Condition range(String field, Object from, boolean includeFrom, Object to, boolean includeTo) {
        Condition c = range(field, from, to);
        c.includeFrom = includeFrom;
        c.includeTo = includeTo;
        return c;
    }

    public static Condition gt(String field, Object from) {
        Condition c = range(field, from, null);
        c.includeFrom = false;
        return c;
    }

    public static Condition gte(String field, Object from) {
        return range(field, from, null);
    }

    public static Condition lt(String field, Object to) {
        Condition c = range(field, null, to);
        c.includeTo = false;
        return c;
    }

    public static Condition lte(String field, Object to) {
        return range(field, null, to);
    }

    /** 日期区间（9.x DateRangeQuery），边界为 String/epoch-millis 表达式 */
    public static Condition dateRange(String field, Object from, boolean includeFrom, Object to, boolean includeTo) {
        Condition c = range(field, from, includeFrom, to, includeTo);
        c.date = true;
        return c;
    }

    public static Condition exists(String field) {
        Condition c = new Condition();
        c.type = Type.EXISTS;
        c.field = field;
        return c;
    }

    public static Condition notExists(String field) {
        Condition c = exists(field);
        c.missing = true;
        return c;
    }

    public static Condition ids(String... ids) {
        Condition c = new Condition();
        c.type = Type.IDS;
        c.values = Arrays.asList(ids);
        return c;
    }

    /** nested 条件：path 为 nested 字段路径，inner 为内层查询（默认 must 语义组合） */
    public static Condition nested(String path, Condition... inner) {
        Condition c = new Condition();
        c.type = Type.NESTED;
        c.path = path;
        c.inner = new ArrayList<>(Arrays.asList(inner));
        return c;
    }

    /** 原生查询 JSON，覆盖任意 DSL 场景 */
    public static Condition raw(String json) {
        Condition c = new Condition();
        c.type = Type.RAW;
        c.rawJson = json;
        return c;
    }

    /** Java 值 -> ES FieldValue（按类型路由：bool/整型/浮点/字符串） */
    public static FieldValue toFieldValue(Object v) {
        if (v instanceof FieldValue fv) {
            return fv;
        }
        if (v == null) {
            return FieldValue.of((String) null);
        }
        if (v instanceof Boolean b) {
            return FieldValue.of(b);
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return FieldValue.of(((Number) v).longValue());
        }
        if (v instanceof Number n) {
            return FieldValue.of(n.doubleValue());
        }
        return FieldValue.of(v.toString());
    }

    public static List<FieldValue> toFieldValues(Collection<?> values) {
        return values.stream().map(Condition::toFieldValue).collect(Collectors.toList());
    }
}
