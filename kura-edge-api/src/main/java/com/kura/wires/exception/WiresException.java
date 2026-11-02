/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.exception;

/**
 * 唯一业务异常类型：由 core/services 抛出，rest 层
 * {@code GlobalExceptionHandler} 统一转换为 {@code {code, message}} 响应
 * （对照上游 {@code KuraException} 与 {@code DefaultExceptionHandler}）。
 */
public class WiresException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * 创建业务异常。
     *
     * @param errorCode 业务错误码
     * @param message 错误描述
     */
    public WiresException(final ErrorCode errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 创建带原因的业务异常。
     *
     * @param errorCode 业务错误码
     * @param message 错误描述
     * @param cause 根因
     */
    public WiresException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 返回业务错误码。
     *
     * @return 错误码
     */
    public ErrorCode getErrorCode() {
        return this.errorCode;
    }
}
