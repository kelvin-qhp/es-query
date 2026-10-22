package com.example.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 JDK 内置 HttpServer 模拟 ES，验证工厂能真正建连并生效超时配置。
 * 只加载 ElasticsearchConfig 一个配置类，避免 @SpringBootTest 引入 Nacos/真实集群配置。
 */
//@ExtendWith(SpringExtension.class)
//@ContextConfiguration(classes = ElasticsearchConfig.class)
@SpringBootTest
class EsClientFactoryTest {

    private static final String INFO_JSON = """
            {
              "name": "stub-node",
              "cluster_name": "stub-cluster",
              "cluster_uuid": "stub-uuid",
              "version": {
                "number": "9.0.3",
                "build_flavor": "default",
                "build_type": "docker",
                "build_hash": "0000000000000000000000000000000000000000",
                "build_date": "2025-01-01T00:00:00.000000000Z",
                "build_snapshot": false,
                "lucene_version": "10.1.0",
                "minimum_wire_compatibility_version": "8.18.0",
                "minimum_index_compatibility_version": "8.0.0"
              },
              "tagline": "You Know, for Search"
            }
            """;

    private static HttpServer server;
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static volatile boolean slowResponse;

    @Autowired
    EsProperties esProperties;


    private static void startStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (slowResponse) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = INFO_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
                try {
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (IOException ignored) {
                    // 客户端超时断开后写响应会失败，属预期
                }
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("启动 ES 桩服务失败", e);
        }
    }

    @BeforeEach
    void resetStub() {
        authorization.set(null);
        slowResponse = false;
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    void createTest2(){
        ElasticsearchClient elasticsearchClient = EsClientFactory.create2(esProperties);
        assertNotNull(elasticsearchClient);

    }


    @Test
    void createTest() throws Exception {
        assertEquals("127.0.0.1:" + server.getAddress().getPort(), esProperties.getNodes().get(0));

        try (ElasticsearchClient client = EsClientFactory.create2(esProperties)) {
            assertEquals("9.0.3", client.info().version().number());
        }
    }

    @Test
    void createBuildsWorkingClientWithBasicAuth() throws Exception {
//        EsProperties props = props().basicAuth("elastic", "secret");

        try (ElasticsearchClient client = EsClientFactory.create(esProperties)) {
            InfoResponse info = client.info();
            assertEquals("stub-node", info.name());
            assertEquals("stub-cluster", info.clusterName());
            assertEquals("9.0.3", info.version().number());
        }

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, authorization.get());
    }

    @Test
    void create2BuildsWorkingClientWithConfiguredTimeouts() throws Exception {
//        EsProperties props = props();
//        props.connectTimeoutMillis = 500;
//        props.socketTimeoutMillis = 2000;

        try (ElasticsearchClient client = EsClientFactory.create2(esProperties)) {
            InfoResponse info = client.info();
            assertEquals("stub-node", info.name());
            assertEquals("9.0.3", info.version().number());
        }
    }

    @Test
    void create2AppliesResponseTimeout() throws Exception {
//        EsProperties props = props();
//        props.connectTimeoutMillis = 500;
//        props.socketTimeoutMillis = 300;
        slowResponse = true;

        try (ElasticsearchClient client = EsClientFactory.create2(esProperties)) {
            long start = System.nanoTime();
            assertThrows(IOException.class, client::info);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < 1400,
                    "socketTimeoutMillis 未生效，耗时 " + elapsedMillis + "ms");
        }
    }

}
