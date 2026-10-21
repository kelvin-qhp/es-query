package com.example.es.exception;

/**
 * ES 查询服务统一异常。
 */
public class EsException extends RuntimeException {

    public EsException(String message) {
        super(message);
    }

    public EsException(String message, Throwable cause) {
        super(message, cause);
    }
}
