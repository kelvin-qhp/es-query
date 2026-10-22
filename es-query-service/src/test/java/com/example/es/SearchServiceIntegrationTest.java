package com.example.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.es.core.Agg;
import com.example.es.dto.AggResult;
import com.example.es.dto.Condition;
import com.example.es.dto.SearchRequestDto;
import com.example.es.dto.SearchResultDto;
import com.example.es.service.SearchService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 端到端示例：本地起 ES 9.x（如 docker run -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false docker.elastic.co/elasticsearch/elasticsearch:9.0.3）
 * 运行；客户端与 SearchService 均由 Spring 容器注入。
 */
@SpringBootTest(properties = "es.nodes=localhost:9200")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchServiceIntegrationTest {

    @Autowired
    ElasticsearchClient client;
    @Autowired
    SearchService service;

    @BeforeAll
    void setUp() throws Exception {
        if (!client.indices().exists(e -> e.index("it_products")).value()) {
            client.indices().create(c -> c.index("it_products"));
        }
        for (int i = 0; i < 30; i++) {
            ObjectNode doc = JsonUtilsNode.object();
            doc.put("title", "商品 " + i);
            doc.put("brand", i % 2 == 0 ? "huawei" : "xiaomi");
            doc.put("price", i * 10);
            doc.put("status", 1);
            doc.put("create_time", "2026-10-" + String.format("%02d", i % 28 + 1));
            String id = String.valueOf(i);
            client.index(ir -> ir.index("it_products").id(id).document(doc));
        }
        client.indices().refresh(r -> r.index("it_products"));
    }

    @AfterAll
    void tearDown() throws Exception {
        client.indices().delete(d -> d.index("it_products"));
        // ElasticsearchClient 由 Spring 容器负责关闭
    }

    @Test
    void fullFlow() throws Exception {
        // 1. 条件查询 + 高亮
        SearchRequestDto dto = new SearchRequestDto("it_products")
                .keyword("商品", "title")
                .filter(Condition.term("status", 1), Condition.range("price", 0, 200))
                .mustNot(Condition.term("brand", "xiaomi"))
                .sortAsc("price")
                .highlight("title")
                .page(1, 5);
        List<ObjectNode> docs = service.search(dto, ObjectNode.class);
        System.out.println("docs=" + docs.size());

        // 2. count
        System.out.println("total=" + service.count(dto));

        // 3. 聚合：terms + 子聚合 + 日期趋势
        AggResult agg = service.aggregate(new SearchRequestDto("it_products"), List.of(
                Agg.terms("by_brand", "keyword_brand").size(10).orderCountDesc()
                        .sub(Agg.avg("avg_price", "price")),
                Agg.dateHistogram("trend", "create_time",
                        co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval.Day,
                        "yyyy-MM-dd").sub(Agg.cardinality("uv", "brand")),
                Agg.stats("price_stats", "price")));
        agg.buckets("by_brand").forEach(b -> System.out.println(b.key + " -> " + b.subAggs));
        System.out.println(agg.stats("price_stats"));

        // 4. search_after 深分页
        SearchRequestDto after = new SearchRequestDto("it_products").size(5).sortAsc("price");
        SearchResultDto<ObjectNode> p1 = service.searchAfter(after, ObjectNode.class);
        after.searchAfter = p1.searchAfterCursor;
        SearchResultDto<ObjectNode> p2 = service.searchAfter(after, ObjectNode.class);
        System.out.println("page2 first=" + p2.documents.get(0));

        // 5. 全量遍历
        long total = service.searchAll(new SearchRequestDto("it_products").sortAsc("price"),
                10, ObjectNode.class, batch -> {
                });
        System.out.println("scrolled=" + total);
    }

    /** 测试辅助：JSON 文档构造 */
    static class JsonUtilsNode {
        static ObjectNode object() {
            return com.example.es.util.JsonUtils.mapper().createObjectNode();
        }
    }
}
