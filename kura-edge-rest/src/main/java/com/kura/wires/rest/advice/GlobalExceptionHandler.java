/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.advice;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.rest.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：领域异常、Bean Validation 与兜底异常统一转
 * {@code {code, message}} 响应（对照上游 DefaultExceptionHandler，Rules 07）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 领域异常 → 错误码对应 HTTP 状态。
     *
     * @param exception 领域异常
     * @return 错误响应
     */
    @ExceptionHandler(WiresException.class)
    public ResponseEntity<ApiResponse<Void>> handleWiresException(final WiresException exception) {
        final int httpStatus = exception.getErrorCode().getHttpStatus();
        return ResponseEntity.status(HttpStatus.valueOf(httpStatus))
                .body(ApiResponse.error(Integer.parseInt(exception.getErrorCode().getCode()), exception.getMessage()));
    }

    /**
     * Bean Validation 失败 → 400001。
     *
     * @param exception 校验异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(final MethodArgumentNotValidException exception) {
        final String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((left, right) -> left + "; " + right).orElse("request validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.error(400001, "参数校验失败: " + detail));
    }

    /**
     * 请求体不可读（JSON 非法等）→ 400001。
     *
     * @param exception 消息转换异常
     * @return 400 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(final HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400001, "请求体非法: " + exception.getMessage()));
    }

    /**
     * 方法安全拒绝（@PreAuthorize）→ 403001。
     *
     * @param exception 拒绝访问异常
     * @return 403 错误响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(final AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(403001, "无权限：需要 wires.admin 角色"));
    }

    /**
     * 认证失败 → 401001。
     *
     * @param exception 认证异常
     * @return 401 错误响应
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(final AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401001, "未认证：请使用 HTTP Basic 提供 wires.admin 凭据"));
    }

    /**
     * 静态资源缺失（例如 {@code /favicon.ico}、{@code /assets/*.js} 不在 jar 里）→ 404001，
     * 不再落到兜底 500：缺文件是客户端路径问题，不是服务故障。
     *
     * @param exception 资源未找到异常
     * @return 404 错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(final NoResourceFoundException exception) {
        final ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus())).body(ApiResponse
                .error(Integer.parseInt(code.getCode()), "资源不存在: " + exception.getResourcePath()));
    }

    /**
     * SSE 连接被客户端断开（RFC 007 的组件日志流）：响应头已固定为 {@code text/event-stream}，
     * 写统一响应体会再抛 {@code HttpMessageNotWritableException}。这里只记 debug 交给容器收尾，
     * 避免每次关闭日志面板都在服务端打一条 ERROR。
     *
     * @param exception 异步响应已不可用的信号
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(final AsyncRequestNotUsableException exception) {
        log.debug("Async response is no longer usable", exception);
    }

    /**
     * 兜底异常 → 500001。
     *
     * @param exception 未分类异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleFallback(final Exception exception) {
        log.error("Unhandled exception while processing request", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500001, "服务器内部错误"));
    }
}
