package com.example.es.util;

import co.elastic.clients.elasticsearch._types.query_dsl.DateRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.DecayFunction;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.NumberRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch._types.query_dsl.TermRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.UntypedDecayFunction;
import co.elastic.clients.json.JsonData;
import com.example.es.exception.EsException;
import com.example.es.dto.Condition;
import com.example.es.dto.FunctionScore;
import com.example.es.dto.ScoreFunction;
import com.example.es.dto.SearchRequestDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将 Condition / SearchRequestDto 转换为官方客户端的 Query（ES java client 9.x）。
 */
public final class QueryBuilderUtil {

    private QueryBuilderUtil() {
    }

    /** 单条 Condition -> Query */
    public static Query build(Condition c) {
        return switch (c.type) {
            case TERM -> Query.of(q -> q.term(t -> t.field(c.field).value(Condition.toFieldValue(c.value))));
            case TERMS -> Query.of(q -> q.terms(t -> t
                    .field(c.field)
                    .terms(TermsQueryField.of(f -> f.value(Condition.toFieldValues(c.values))))));
            case MATCH -> Query.of(q -> q.match(m -> {
                m.field(c.field).query(String.valueOf(c.value)).operator(op(c.operator));
                if (c.fuzziness != null) {
                    m.fuzziness(c.fuzziness);
                }
                return m;
            }));
            case MATCH_PHRASE -> Query.of(q -> q.matchPhrase(m -> m.field(c.field).query(String.valueOf(c.value))));
            case MATCH_BOOL_PREFIX -> Query.of(q -> q.matchBoolPrefix(m -> m.field(c.field).query(String.valueOf(c.value))));
            case PREFIX -> Query.of(q -> q.prefix(p -> p.field(c.field).value(String.valueOf(c.value))));
            case WILDCARD -> Query.of(q -> q.wildcard(w -> w.field(c.field).value(String.valueOf(c.value))));
            case REGEXP -> Query.of(q -> q.regexp(r -> r.field(c.field).value(String.valueOf(c.value))));
            case RANGE -> buildRange(c);
            case EXISTS -> c.missing
                    ? Query.of(q -> q.bool(b -> b.mustNot(mn -> mn.exists(e -> e.field(c.field)))))
                    : Query.of(q -> q.exists(e -> e.field(c.field)));
            case IDS -> Query.of(q -> q.ids(i -> i.values(c.values.stream().map(String::valueOf)
                    .collect(Collectors.toList()))));
            case NESTED -> {
                Query inner = and(c.inner);
                yield Query.of(q -> q.nested(n -> n.path(c.path).query(inner)));
            }
            case RAW -> Query.of(q -> q.withJson(new java.io.StringReader(c.rawJson)));
        };
    }

    /** 9.x RangeQuery 拆分为 number / date / term 三种类型化区间 */
    private static Query buildRange(Condition c) {
        boolean numeric = c.from instanceof Number || c.to instanceof Number;
        if (numeric) {
            NumberRangeQuery.Builder nb = new NumberRangeQuery.Builder();
            nb.field(c.field);
            if (c.from instanceof Number n) {
                if (c.includeFrom) {
                    nb.gte(n.doubleValue());
                } else {
                    nb.gt(n.doubleValue());
                }
            }
            if (c.to instanceof Number n) {
                if (c.includeTo) {
                    nb.lte(n.doubleValue());
                } else {
                    nb.lt(n.doubleValue());
                }
            }
            return Query.of(q -> q.range(r -> r.number(nb.build())));
        }
        if (c.date) {
            DateRangeQuery.Builder db = new DateRangeQuery.Builder();
            db.field(c.field);
            applyStringBounds(db, c);
            return Query.of(q -> q.range(r -> r.date(db.build())));
        }
        TermRangeQuery.Builder tb = new TermRangeQuery.Builder();
        tb.field(c.field);
        applyStringBounds(tb, c);
        return Query.of(q -> q.range(r -> r.term(tb.build())));
    }

    private static void applyStringBounds(DateRangeQuery.Builder b, Condition c) {
        if (c.from != null) {
            String from = String.valueOf(c.from);
            if (c.includeFrom) {
                b.gte(from);
            } else {
                b.gt(from);
            }
        }
        if (c.to != null) {
            String to = String.valueOf(c.to);
            if (c.includeTo) {
                b.lte(to);
            } else {
                b.lt(to);
            }
        }
    }

    private static void applyStringBounds(TermRangeQuery.Builder b, Condition c) {
        if (c.from != null) {
            String from = String.valueOf(c.from);
            if (c.includeFrom) {
                b.gte(from);
            } else {
                b.gt(from);
            }
        }
        if (c.to != null) {
            String to = String.valueOf(c.to);
            if (c.includeTo) {
                b.lte(to);
            } else {
                b.lt(to);
            }
        }
    }

    /** 条件列表 AND 组合（filter 上下文，不算分） */
    public static Query and(List<Condition> conditions) {
        if (conditions.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        return Query.of(q -> q.bool(b -> {
            conditions.forEach(c -> b.filter(build(c)));
            return b;
        }));
    }

    /** SearchRequestDto -> 顶层 Query */
    public static Query buildDto(SearchRequestDto dto) {
        return applyFunctionScore(dto, buildBaseQuery(dto));
    }

    /** bool 组合或 rawQuery 的基础 query；未配置 function_score 时即为最终 query */
    private static Query buildBaseQuery(SearchRequestDto dto) {
        if (dto.rawQuery != null) {
            return dto.rawQuery;
        }
        boolean empty = dto.keyword == null && dto.must.isEmpty() && dto.should.isEmpty()
                && dto.mustNot.isEmpty() && dto.filter.isEmpty();
        if (empty) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        return Query.of(q -> q.bool(b -> {
            if (dto.keyword != null && !dto.keyword.isBlank()) {
                b.must(m -> m.multiMatch(mm -> {
                    mm.query(dto.keyword);
                    if (!dto.defaultFields.isEmpty()) {
                        mm.fields(dto.defaultFields);
                    }
                    return mm;
                }));
            }
            dto.must.forEach(c -> b.must(build(c)));
            dto.filter.forEach(c -> b.filter(build(c)));
            dto.mustNot.forEach(c -> b.mustNot(build(c)));
            if (!dto.should.isEmpty()) {
                dto.should.forEach(c -> b.should(build(c)));
                b.minimumShouldMatch(String.valueOf(dto.minimumShouldMatch == null ? 1 : dto.minimumShouldMatch));
            }
            return b;
        }));
    }

    private static Operator op(String operator) {
        return "OR".equalsIgnoreCase(operator) ? Operator.Or : Operator.And;
    }

    // ==================== function_score ====================

    /** 配置了 functionScore 时把基础 query 包一层 function_score，否则原样返回 */
    private static Query applyFunctionScore(SearchRequestDto dto, Query base) {
        FunctionScore fs = dto.functionScore;
        if (fs == null || fs.functions.isEmpty()) {
            return base;
        }
        return Query.of(q -> q.functionScore(f -> {
            f.query(base);
            fs.functions.forEach(sf -> f.functions(buildScoreFunction(sf)));
            if (fs.scoreMode != null) {
                f.scoreMode(scoreMode(fs.scoreMode));
            }
            if (fs.boostMode != null) {
                f.boostMode(boostMode(fs.boostMode));
            }
            if (fs.maxBoost != null) {
                f.maxBoost(fs.maxBoost);
            }
            if (fs.minScore != null) {
                f.minScore(fs.minScore);
            }
            return f;
        }));
    }

    /** ScoreFunction -> ES FunctionScore 变体（9.x：weight/filter 在外层，函数体用 ContainerBuilder 链式设置） */
    private static co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore buildScoreFunction(ScoreFunction sf) {
        return co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore.of(f -> {
            Query filter = buildFilter(sf);
            if (filter != null) {
                f.filter(filter);
            }
            if (sf.weight != null) {
                f.weight(sf.weight);
            }
            switch (sf.type) {
                case GAUSS -> f.gauss(DecayFunction.of(d -> d.untyped(untypedDecay(sf))));
                case EXP -> f.exp(DecayFunction.of(d -> d.untyped(untypedDecay(sf))));
                case LINEAR -> f.linear(DecayFunction.of(d -> d.untyped(untypedDecay(sf))));
                case FIELD_VALUE -> f.fieldValueFactor(v -> {
                    v.field(sf.valueField);
                    if (sf.factor != null) {
                        v.factor(sf.factor);
                    }
                    if (sf.missing != null) {
                        v.missing(sf.missing);
                    }
                    if (sf.modifier != null) {
                        v.modifier(sf.modifier);
                    }
                    return v;
                });
                case SCRIPT -> f.scriptScore(s -> s.script(buildScript(sf)));
                case RANDOM -> f.randomScore(r -> {
                    if (sf.seedField != null) {
                        r.field(sf.seedField);
                    }
                    return r;
                });
                case WEIGHT -> {
                    // 纯权重函数：只设 weight，不带函数体
                }
            }
            return f;
        });
    }

    private static co.elastic.clients.elasticsearch._types.Script buildScript(ScoreFunction sf) {
        return co.elastic.clients.elasticsearch._types.Script.of(sc -> {
            sc.source(src -> src.scriptString(sf.scriptSource));
            if (sf.scriptParams != null && !sf.scriptParams.isEmpty()) {
                Map<String, JsonData> params = new LinkedHashMap<>();
                sf.scriptParams.forEach((k, v) -> params.put(k, JsonData.of(v)));
                sc.params(params);
            }
            return sc;
        });
    }

    /** filter + filters AND 组合；未设置返回 null */
    private static Query buildFilter(ScoreFunction sf) {
        if (sf.filter == null && sf.filters.isEmpty()) {
            return null;
        }
        List<Condition> all = new ArrayList<>();
        if (sf.filter != null) {
            all.add(sf.filter);
        }
        all.addAll(sf.filters);
        return and(all);
    }

    /** 衰减函数统一走 UntypedDecayFunction（JsonData 原样序列化，与 number/date/geo 类型的 wire JSON 相同） */
    private static UntypedDecayFunction untypedDecay(ScoreFunction sf) {
        return UntypedDecayFunction.of(d -> {
            d.field(sf.field);
            d.placement(p -> {
                if (sf.origin != null) {
                    p.origin(JsonData.of(sf.origin));
                }
                if (sf.scale != null) {
                    p.scale(JsonData.of(sf.scale));
                }
                if (sf.offset != null) {
                    p.offset(JsonData.of(sf.offset));
                }
                if (sf.decay != null) {
                    p.decay(sf.decay);
                }
                return p;
            });
            return d;
        });
    }

    /** DSL 枚举常量为首字母大写形式（Sum/Multiply/Replace...） */
    private static String enumName(String mode) {
        String v = mode.trim().toLowerCase();
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static FunctionScoreMode scoreMode(String mode) {
        try {
            return FunctionScoreMode.valueOf(enumName(mode));
        } catch (IllegalArgumentException e) {
            throw new EsException("非法 scoreMode: " + mode + "，可选 multiply/sum/avg/first/max/min", e);
        }
    }

    private static FunctionBoostMode boostMode(String mode) {
        try {
            return FunctionBoostMode.valueOf(enumName(mode));
        } catch (IllegalArgumentException e) {
            throw new EsException("非法 boostMode: " + mode + "，可选 multiply/replace/sum/avg/max/min", e);
        }
    }
}
