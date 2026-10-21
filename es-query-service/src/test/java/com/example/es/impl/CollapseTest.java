package com.example.es.impl;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.example.es.dto.Collapse;
import com.example.es.dto.Condition;
import com.example.es.dto.HitWrapper;
import com.example.es.dto.SearchRequestDto;
import com.example.es.util.JsonUtils;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * collapse 折叠 DSL 生成与结果读取单测。
 */
class CollapseTest {

    private static final JsonpMapper MAPPER = new JacksonJsonpMapper(JsonUtils.mapper());

    private static String toJson(SearchRequestDto dto) {
        SearchRequest req = new SearchServiceImpl(null).toRequest(dto, null);
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = MAPPER.jsonProvider().createGenerator(sw)) {
            req.serialize(gen, MAPPER);
        }
        return sw.toString();
    }

    @Test
    void collapseWithInnerHitsAndSort() {
        SearchRequestDto dto = new SearchRequestDto("orders")
                .filter(Condition.term("status", 1))
                .collapse(Collapse.on("user_id").innerHits("others", 3)
                        .innerHitsFields("order_no", "amount"))
                .sortDesc("amount");
        String json = toJson(dto);
        assertTrue(json.contains("\"collapse\":{\"field\":\"user_id\""), json);
        // 9.x 客户端把 inner_hits 序列化为数组（服务端 9.x 起支持多个 inner_hits）
        assertTrue(json.contains("\"inner_hits\":[{\"name\":\"others\",\"size\":3"), json);
        assertTrue(json.contains("\"includes\":[\"order_no\",\"amount\"]"), json);
        assertTrue(json.contains("\"amount\":{\"order\":\"desc\"}"), json);
    }

    @Test
    void defaultInnerHitsName() {
        String json = toJson(new SearchRequestDto("orders")
                .collapse(Collapse.on("user_id").innerHits(2)));
        assertTrue(json.contains("\"name\":\"collapse_inner\""), json);
        assertTrue(json.contains("\"size\":2"), json);
    }

    @Test
    void collapseWithoutInnerHits() {
        String json = toJson(new SearchRequestDto("orders").collapse("user_id"));
        assertTrue(json.contains("\"collapse\":{\"field\":\"user_id\"}"), json);
        assertFalse(json.contains("inner_hits"), json);
    }

    @Test
    void noCollapseKeepsRequestClean() {
        String json = toJson(new SearchRequestDto("orders").filter(Condition.term("status", 1)));
        assertFalse(json.contains("collapse"), json);
    }

    @Test
    void hitWrapperExposesInnerHits() {
        Hit<String> hit = Hit.of(h -> h.id("o-1").index("orders").source("main")
                .innerHits("others", ihr -> ihr.hits(hm -> hm
                        .total(t -> t.value(1).relation(TotalHitsRelation.Eq))
                        .hits(hh -> hh.id("o-2").index("orders")))));
        HitWrapper<String> w = HitWrapper.from(hit);
        assertEquals(1, w.innerHits().get("others").hits().hits().size());
        assertEquals("o-2", w.innerHits().get("others").hits().hits().get(0).id());
    }
}
