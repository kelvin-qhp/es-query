package com.example.es.util;

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.example.es.core.Agg;
import com.example.es.dto.Condition;
import com.example.es.dto.FunctionScore;
import com.example.es.dto.ScoreFunction;
import com.example.es.dto.SearchRequestDto;
import com.example.es.exception.EsException;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询/聚合 DSL 生成单元测试（不需要 ES 服务）。
 */
class QueryBuilderUtilTest {

    private static final JsonpMapper MAPPER = new JacksonJsonpMapper(JsonUtils.mapper());

    static String toJson(Object jsonp) {
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = MAPPER.jsonProvider().createGenerator(sw)) {
            ((co.elastic.clients.json.JsonpSerializable) jsonp).serialize(gen, MAPPER);
        }
        return sw.toString();
    }

    @Test
    void termAndTermsAndRange() {
        Query q = QueryBuilderUtil.build(Condition.term("status", 1));
        assertTrue(toJson(q).contains("\"term\""));

        Query terms = QueryBuilderUtil.build(Condition.terms("brand", "a", "b"));
        String json = toJson(terms);
        assertTrue(json.contains("\"terms\""));
        assertTrue(json.contains("\"a\""));

        Query range = QueryBuilderUtil.build(Condition.range("price", 10, 100));
        String rj = toJson(range);
        assertTrue(rj.contains("\"gte\":10"));
        assertTrue(rj.contains("\"lte\":100"));

        Query gt = QueryBuilderUtil.build(Condition.gt("price", 10));
        assertTrue(toJson(gt).contains("\"gt\":10"));
    }

    @Test
    void dateRangeUsesDateQuery() {
        Query q = QueryBuilderUtil.build(
                Condition.dateRange("create_time", "2026-01-01", true, "2026-02-01", false));
        String json = toJson(q);
        assertTrue(json.contains("\"range\""), json);
        assertTrue(json.contains("2026-01-01"));
        assertTrue(json.contains("\"lt\":\"2026-02-01\""));
    }

    @Test
    void boolComposition() {
        SearchRequestDto dto = new SearchRequestDto("idx")
                .keyword("手机", "title^3")
                .must(Condition.match("desc", "5G"))
                .filter(Condition.term("status", 1))
                .mustNot(Condition.term("deleted", true))
                .should(1, Condition.match("brand", "huawei"), Condition.match("brand", "xiaomi"));
        String json = toJson(QueryBuilderUtil.buildDto(dto));
        assertTrue(json.contains("\"bool\""));
        assertTrue(json.contains("multi_match") || json.contains("multiMatch"), json);
        assertTrue(json.contains("minimum_should_match") || json.contains("minimumShouldMatch"), json);
    }

    @Test
    void existsAndMissing() {
        assertTrue(toJson(QueryBuilderUtil.build(Condition.exists("f"))).contains("exists"));
        assertTrue(toJson(QueryBuilderUtil.build(Condition.notExists("f"))).contains("must_not")
                || toJson(QueryBuilderUtil.build(Condition.notExists("f"))).contains("mustNot"));
    }

    @Test
    void rawQueryEscapeHatch() {
        Query q = QueryBuilderUtil.build(Condition.raw("{\"term\":{\"a\":{\"value\":\"b\"}}}"));
        assertTrue(toJson(q).contains("\"b\""));
    }

    @Test
    void aggBuild() {
        Agg terms = Agg.terms("by_brand", "brand").size(20).orderCountDesc()
                .sub(Agg.avg("avg_price", "price"));
        String json = toJson(terms.build());
        assertTrue(json.contains("\"terms\""));
        assertTrue(json.contains("\"_count\""));
        assertTrue(json.contains("avg"));

        Agg hist = Agg.dateHistogram("trend", "create_time",
                co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval.Day, "yyyy-MM-dd");
        assertTrue(toJson(hist.build()).contains("date_histogram"));

        Agg range = Agg.range("price_band", "price", 0, 50, 100);
        String rj = toJson(range.build());
        assertTrue(rj.contains("range"));
        assertTrue(rj.contains("\"from\":50.0") || rj.contains("\"from\":50"));

        Agg dr = Agg.dateRange("time_band", "create_time", "yyyy-MM-dd",
                Map.of("recent", new String[]{"now-7d", "now"}));
        assertTrue(toJson(dr.build()).contains("date_range"));
    }

    @Test
    void aggMetricAndComposite() {
        Agg card = Agg.cardinality("uv", "user_id");
        assertTrue(toJson(card.build()).contains("cardinality"));

        Agg ext = Agg.extendedStats("price_stats", "price");
        assertTrue(toJson(ext.build()).contains("extended_stats"));

        Agg pct = Agg.percentiles("price_pct", "price");
        assertTrue(toJson(pct.build()).contains("percentiles"));

        Agg hist = Agg.histogram("price_hist", "price", 50);
        String hj = toJson(hist.build());
        assertTrue(hj.contains("histogram"), hj);
        assertTrue(hj.contains("\"interval\":50"), hj);

        Agg comp = Agg.composite("by_user",
                Agg.sources("user", Agg.termsSource("user_id")), Map.of("user", "u-1"))
                .sub(Agg.sum("amount", "amount"));
        String cj = toJson(comp.build());
        assertTrue(cj.contains("composite"), cj);
        assertTrue(cj.contains("\"after\":{\"user\":\"u-1\"}"), cj);
        assertTrue(cj.contains("sum"), cj);
    }

    @Test
    void nestedFilterQuery() {
        Query q = QueryBuilderUtil.build(Condition.nested("items",
                Condition.term("items.color", "red"),
                Condition.range("items.size", 2, 5)));
        String json = toJson(q);
        assertTrue(json.contains("\"nested\""), json);
        assertTrue(json.contains("\"path\":\"items\""), json);
        assertTrue(json.contains("\"items.color\""), json);
        assertTrue(json.contains("\"gte\":2"), json);
        assertTrue(json.contains("\"lte\":5"), json);
    }

    @Test
    void nestedQueryInFilterContextKeepsBoolStructure() {
        SearchRequestDto dto = new SearchRequestDto("orders")
                .keyword("手机")
                .filter(Condition.term("status", 1),
                        Condition.nested("items", Condition.term("items.warehouse", "hz")));
        String json = toJson(QueryBuilderUtil.buildDto(dto));
        assertTrue(json.contains("\"bool\""), json);
        assertTrue(json.contains("\"filter\""), json);
        assertTrue(json.contains("\"nested\""), json);
        assertTrue(json.indexOf("bool") < json.indexOf("nested"), json);
    }

    @Test
    void nestedAggWithMetricSubAgg() {
        Agg n = Agg.nested("items_agg", "items")
                .sub(Agg.terms("by_color", "items.color").size(5).orderCountDesc(),
                        Agg.avg("avg_price", "items.price"));
        String json = toJson(n.build());
        assertTrue(json.contains("\"nested\":{\"path\":\"items\"}"), json);
        assertTrue(json.contains("\"by_color\":{\"terms\":{\"field\":\"items.color\""), json);
        assertTrue(json.contains("\"avg_price\":{\"avg\":{\"field\":\"items.price\"}}"), json);
        // 两个子聚合都在 nested 的 aggregations 块下（build() 只输出聚合体，最外层名字由聚合 Map 赋）
        assertTrue(json.startsWith("{\"aggregations\":{"), json);
    }

    @Test
    void nestedAggWithFilterBucket() {
        Agg n = Agg.nested("items", "items").sub(
                Agg.filter("only_red", QueryBuilderUtil.build(Condition.term("items.color", "red")),
                        Agg.sum("qty", "items.qty")));
        String json = toJson(n.build());
        assertTrue(json.contains("\"nested\":{\"path\":\"items\"}"), json);
        assertTrue(json.contains("\"aggregations\":{\"only_red\""), json);
        assertTrue(json.contains("\"filter\":{\"term\":{\"items.color\":{\"value\":\"red\"}}}"), json);
        assertTrue(json.contains("\"qty\":{\"sum\":{\"field\":\"items.qty\"}}"), json);
        // 三层：nested -> only_red(filter) -> qty(sum)
        assertTrue(json.contains("\"only_red\":{\"aggregations\":{\"qty\""), json);
    }

    @Test
    void filterBucketWithNestedAggInside() {
        Agg f = Agg.filter("sold", QueryBuilderUtil.build(Condition.term("status", 1)))
                .sub(Agg.nested("items", "items").sub(Agg.topHits("samples", 2)));
        String json = toJson(f.build());
        assertTrue(json.contains("\"filter\":{\"term\":{\"status\":{\"value\":1}}}"), json);
        assertTrue(json.contains("\"aggregations\":{\"items\""), json);
        assertTrue(json.contains("\"nested\":{\"path\":\"items\"}"), json);
        assertTrue(json.contains("\"samples\":{\"top_hits\":{\"size\":2}}"), json);
        // 三层：filter -> items(nested) -> samples(top_hits)
        assertTrue(json.contains("\"items\":{\"aggregations\":{\"samples\""), json);
    }

    // ==================== function_score ====================

    @Test
    void gaussDecayWithWeightAndFilter() {
        SearchRequestDto dto = new SearchRequestDto("idx")
                .keyword("手机")
                .functionScore(FunctionScore.of(
                        ScoreFunction.gauss("price", 100, 50, 0.5).when(Condition.term("status", 1))
                                .weight(2.0)));
        String json = toJson(QueryBuilderUtil.buildDto(dto));
        assertTrue(json.contains("function_score") || json.contains("functionScore"), json);
        assertTrue(json.contains("gauss"), json);
        assertTrue(json.contains("\"origin\":100"), json);
        assertTrue(json.contains("\"scale\":50"), json);
        assertTrue(json.contains("\"decay\":0.5"), json);
        assertTrue(json.contains("\"weight\":2.0"), json);
        assertTrue(json.contains("\"filter\""), json);
    }

    @Test
    void dateDecayAsJsonStrings() {
        SearchRequestDto dto = new SearchRequestDto("idx")
                .must(Condition.match("title", "news"))
                .functionScore(FunctionScore.of(
                        ScoreFunction.dateGauss("create_time", "now", "30d", 0.5).offset("7d")));
        String json = toJson(QueryBuilderUtil.buildDto(dto));
        assertTrue(json.contains("\"origin\":\"now\""), json);
        assertTrue(json.contains("\"scale\":\"30d\""), json);
        assertTrue(json.contains("\"offset\":\"7d\""), json);
    }

    @Test
    void fieldValueAndScriptAndModes() {
        SearchRequestDto dto = new SearchRequestDto("idx")
                .keyword("abc")
                .functionScore(FunctionScore.of(
                        ScoreFunction.fieldValue("popularity", 1.2, 1.0).modifier(FieldValueFactorModifier.Log1p),
                        ScoreFunction.script("params.factor * _score", Map.of("factor", 3)))
                        .scoreMode("sum").boostMode("replace").maxBoost(10.0).minScore(1.0));
        String json = toJson(QueryBuilderUtil.buildDto(dto));
        assertTrue(json.contains("field_value_factor") || json.contains("fieldValueFactor"), json);
        assertTrue(json.contains("script_score") || json.contains("scriptScore"), json);
        assertTrue(json.contains("log1p"), json);
        assertTrue(json.contains("params.factor * _score"), json);
        assertTrue(json.contains("\"score_mode\":\"sum\"") || json.contains("\"scoreMode\":\"sum\""), json);
        assertTrue(json.contains("\"boost_mode\":\"replace\"") || json.contains("\"boostMode\":\"replace\""), json);
        assertTrue(json.contains("max_boost") || json.contains("maxBoost"), json);
    }

    @Test
    void wrapsRawQueryAndPureWeight() {
        SearchRequestDto dto = new SearchRequestDto("idx");
        dto.rawQuery = Query.of(q -> q.matchAll(m -> m));
        dto.functionScore = FunctionScore.of(ScoreFunction.pureWeight(3.0))
                .weight(5.0, Condition.term("vip", true));
        String json = toJson(QueryBuilderUtil.buildDto(dto));
        assertTrue(json.contains("match_all") || json.contains("matchAll"), json);
        assertTrue(json.contains("\"weight\":3.0"), json);
        assertTrue(json.contains("\"weight\":5.0"), json);
    }

    @Test
    void noFunctionScoreKeepsPlainQuery() {
        String json = toJson(QueryBuilderUtil.buildDto(new SearchRequestDto("idx").keyword("a")));
        assertFalse(json.contains("function_score"), json);
    }

    @Test
    void illegalFunctionScoreModeThrows() {
        SearchRequestDto dto = new SearchRequestDto("idx")
                .keyword("a")
                .functionScore(FunctionScore.of(ScoreFunction.random("_seq_no")).scoreMode("bad"));
        assertThrows(EsException.class, () -> QueryBuilderUtil.buildDto(dto));
    }
}
