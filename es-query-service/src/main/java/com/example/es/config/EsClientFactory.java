package com.example.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.example.es.util.JsonUtils;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * ElasticsearchClient 工厂（elasticsearch-java 9.x 内建 Transport，基于 httpclient5）。
 * 不绑定 Spring：@Bean 场景直接调用 create()，也可手动 new EsProperties() 后构建。
 */
public final class EsClientFactory {

    private EsClientFactory() {
    }

    public static ElasticsearchClient create(EsProperties props) {
        List<URI> nodes = props.nodes.stream()
                .map(node -> URI.create(toUri(node, props.scheme)))
                .toList();
        return ElasticsearchClient.of(b -> {
            b.hosts(nodes)
                    .jsonMapper(new JacksonJsonpMapper(JsonUtils.mapper()))
                    .useCompression(true);
            if (props.apiKey != null && !props.apiKey.isBlank()) {
                b.apiKey(props.apiKey);
            } else if (props.username != null && !props.username.isBlank()) {
                b.usernameAndPassword(props.username, props.password);
            }
            if (!"http".equals(props.scheme) && !props.sslVerify) {
                b.sslContext(trustAllSslContext());
            }
            return b;
        });
    }

    private static String toUri(String node, String scheme) {
        if (node.startsWith("http")) {
            return node;
        }
        return scheme + "://" + node;
    }

    private static SSLContext trustAllSslContext() {
        try {
            javax.net.ssl.TrustManager[] trustAll = {new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("构建 SSLContext 失败", e);
        }
    }
}
