/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 统一响应体 {@code {code, message, data}}（Rules 07）：{@code code=0} 表示成功，
 * 业务错误为 6 位错误码（HTTP 状态码 + 3 位业务序号）。
 *
 * @param <T> 负载类型
 */
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;

    private ApiResponse(final int code, final String message, final T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建成功响应（code=0）。
     *
     * @param data 负载
     * @param <T> 负载类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(final T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /**
     * 创建错误响应。
     *
     * @param errorCode 6 位业务错误码
     * @param message 错误描述
     * @param <T> 负载类型
     * @return 错误响应
     */
    public static <T> ApiResponse<T> error(final int errorCode, final String message) {
        return new ApiResponse<>(errorCode, message, null);
    }

    public int getCode() {
        return this.code;
    }

    public String getMessage() {
        return this.message;
    }

    public T getData() {
        return this.data;
    }
}
