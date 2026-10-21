package com.example.es.config;

import com.example.es.service.SearchService;
import com.example.es.impl.SearchServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Spring Boot 装配示例（无 Spring 环境时可直接用 EsClientFactory + new SearchServiceImpl）。
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${es.nodes:localhost:9200}")
    private String nodes;
    @Value("${es.scheme:http}")
    private String scheme;
    @Value("${es.username:}")
    private String username;
    @Value("${es.password:}")
    private String password;
    @Value("${es.api-key:}")
    private String apiKey;
    @Value("${es.connect-timeout-millis:5000}")
    private int connectTimeout;
    @Value("${es.socket-timeout-millis:30000}")
    private int socketTimeout;
    @Value("${es.ssl-verify:true}")
    private boolean sslVerify;

    @Bean
    public EsProperties esProperties() {
        EsProperties props = new EsProperties();
        props.nodes = Arrays.stream(nodes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        props.scheme = scheme;
        props.username = username;
        props.password = password;
        props.apiKey = apiKey;
        props.connectTimeoutMillis = connectTimeout;
        props.socketTimeoutMillis = socketTimeout;
        props.sslVerify = sslVerify;
        return props;
    }

    @Bean(destroyMethod = "close")
    public co.elastic.clients.elasticsearch.ElasticsearchClient elasticsearchClient(EsProperties props) {
        return EsClientFactory.create(props);
    }

    @Bean
    public SearchService searchService(co.elastic.clients.elasticsearch.ElasticsearchClient client) {
        return new SearchServiceImpl(client);
    }
}
