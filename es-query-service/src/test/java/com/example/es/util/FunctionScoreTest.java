package com.example.es.util;

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.example.es.exception.EsException;
import com.example.es.dto.Condition;
import com.example.es.dto.FunctionScore;
import com.example.es.dto.ScoreFunction;
import com.example.es.dto.SearchRequestDto;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * function_score DSL 生成单元测试。
 */
class FunctionScoreTest {

    private static final JsonpMapper MAPPER = new JacksonJsonpMapper(JsonUtils.mapper());

    static String toJson(Query q) {
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = MAPPER.jsonProvider().createGenerator(sw)) {
            q.serialize(gen, MAPPER);
        }
        return sw.toString();
    }

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
    void illegalModeThrows() {
        SearchRequestDto dto = new SearchRequestDto("idx")
                .keyword("a")
                .functionScore(FunctionScore.of(ScoreFunction.random("_seq_no")).scoreMode("bad"));
        assertThrows(EsException.class, () -> QueryBuilderUtil.buildDto(dto));
    }
}
