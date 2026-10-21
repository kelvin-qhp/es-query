package com.example.es;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * es-query-service 启动入口（Spring Boot + Nacos 配置中心）。
 */
@SpringBootApplication
public class EsQueryServiceApplicationBootstrap {

    public static void main(String[] args) {
        SpringApplication.run(EsQueryServiceApplicationBootstrap.class, args);
    }
}
