package com.example.es.config;

import java.util.ArrayList;
import java.util.List;

/**
 * ES 连接配置（独立 POJO，Spring 通过 @Bean 装配；也可手动 new）。
 */
public class EsProperties {

    /** 节点列表：host:port 或 ip:port，支持多个做客户端侧负载 */
    public List<String> nodes = new ArrayList<>();
    public String scheme = "http";
    public String username;
    public String password;
    /** 与 username/password 二选一 */
    public String apiKey;
    public Integer connectTimeoutMillis = 5000;
    public Integer socketTimeoutMillis = 30000;
    public Integer maxConnTotal = 100;
    public Integer maxConnPerRoute = 50;
    /** https 且自签证书时跳过校验（仅测试环境） */
    public boolean sslVerify = true;
    /** 慢日志阈值（毫秒），-1 关闭 */
    public long slowLogThresholdMillis = 1000;

    public EsProperties node(String host, int port) {
        nodes.add(host + ":" + port);
        return this;
    }

    public EsProperties basicAuth(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    public EsProperties apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }
}
