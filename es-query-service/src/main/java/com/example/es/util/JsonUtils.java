package com.example.es.util;

import com.example.es.exception.EsException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;

/**
 * 共享 ObjectMapper：与 ES 客户端 JacksonJsonpMapper 使用同一配置，
 * 用于 JsonData -> POJO 的转换。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T convert(JsonNode node, Class<T> clazz) {
        try {
            return MAPPER.treeToValue(node, clazz);
        } catch (IOException e) {
            throw new EsException("文档反序列化失败: " + clazz.getName(), e);
        }
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new EsException("序列化失败", e);
        }
    }
}
