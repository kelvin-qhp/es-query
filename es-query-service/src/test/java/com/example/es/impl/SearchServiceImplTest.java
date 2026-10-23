package com.example.es.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.example.es.config.EsClientFactory;
import com.example.es.config.EsProperties;
import com.example.es.dto.Collapse;
import com.example.es.dto.Condition;
import com.example.es.dto.SearchRequestDto;
import com.example.es.service.SearchService;
import com.example.es.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchServiceImpl 请求 DSL 生成单测（不需要 ES 服务）。
 */
class SearchServiceImplTest {

    @Autowired
    SearchService searchService;

    private static final JsonpMapper MAPPER = new JacksonJsonpMapper(JsonUtils.mapper());

    private static String toJson(SearchRequestDto dto) {
        // toRequest 只做 DSL 构建，不访问客户端；client 注入字段保持 null 即可
        SearchRequest req = new SearchServiceImpl().toRequest(dto, null);
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
    void searchWithoutClassReturnsSearchResponse() throws Exception {
        String searchJson = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": {"total": 1, "successful": 1, "skipped": 0, "failed": 0},
                  "hits": {
                    "total": {"value": 2, "relation": "eq"},
                    "max_score": 1.0,
                    "hits": [
                      {"_index": "orders", "_id": "o-1", "_score": 1.0, "_source": {"order_no": "A1", "amount": 10}},
                      {"_index": "orders", "_id": "o-2", "_score": 1.0, "_source": {"order_no": "B2", "vip": true}}
                    ]
                  }
                }
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = searchJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        ElasticsearchClient client = EsClientFactory.create2(
                new EsProperties().node("127.0.0.1", server.getAddress().getPort()));
        try {
            SearchServiceImpl service = new SearchServiceImpl();
            ReflectionTestUtils.setField(service, "client", client);

            SearchResponse<ObjectNode> response = service.search(new SearchRequestDto("orders"));

            assertEquals(2, response.hits().total().value());
            List<Hit<ObjectNode>> hits = response.hits().hits();
            assertEquals("A1", hits.get(0).source().get("order_no").asText());
            assertEquals(10, hits.get(0).source().get("amount").asInt());
            assertTrue(hits.get(1).source().get("vip").asBoolean());
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
