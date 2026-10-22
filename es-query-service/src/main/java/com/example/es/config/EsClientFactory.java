package com.example.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.example.es.util.JsonUtils;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
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
    public static ElasticsearchClient create2(EsProperties props) {
        List<URI> nodes = props.getNodes().stream()
                .map(node -> URI.create(toUri(node, props.getScheme())))
                .toList();
        Rest5Client restClient = Rest5Client.builder(nodes)
                .setHttpClient(httpClient(props))
                .build();

        Rest5ClientTransport transport = new Rest5ClientTransport(
                restClient, new JacksonJsonpMapper(JsonUtils.mapper())
        );

        return new ElasticsearchClient(transport);
    }

    /**
     * Rest5ClientBuilder 9.0.3 未暴露超时设置，只能自建 httpclient5 异步客户端：
     * connectTimeout 走 ConnectionConfig，socket 超时对应 RequestConfig.responseTimeout。
     */
    private static CloseableHttpAsyncClient httpClient(EsProperties props) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(props.getConnectTimeoutMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(props.getSocketTimeoutMillis()))
                .build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(props.getConnectTimeoutMillis()))
                .build();
        PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnPerRoute(props.getMaxConnPerRoute())
                .setMaxConnTotal(props.getMaxConnTotal())
                .setTlsStrategy(new BasicClientTlsStrategy(sslContext(props)))
                .build();
        return HttpAsyncClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connectionManager)
                .build();
    }

    private static SSLContext sslContext(EsProperties props) {
        if (!"http".equals(props.getScheme()) && !props.isSslVerify()) {
            return trustAllSslContext();
        }
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("默认 SSLContext 不可用", e);
        }
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
