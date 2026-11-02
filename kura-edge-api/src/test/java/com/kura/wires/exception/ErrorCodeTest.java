package com.kura.wires.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 错误码表契约验证（Rules 07：6 位 = HTTP 状态码 + 3 位业务序号）。
 */
class ErrorCodeTest {

    @Test
    void alignHttpStatusCodePrefixWithHttpStatus() {
        for (final ErrorCode code : ErrorCode.values()) {
            final String prefix = code.getCode().substring(0, 3);
            assertThat(String.valueOf(code.getHttpStatus()))
                    .as("错误码 %s 前缀应与 HTTP 状态一致", code)
                    .isEqualTo(prefix);
        }
    }

    @Test
    void exposeKnownCodes() {
        assertThat(ErrorCode.TOPOLOGY_CYCLE.getCode()).isEqualTo("409001");
        assertThat(ErrorCode.PORT_MISMATCH.getCode()).isEqualTo("409002");
        assertThat(ErrorCode.RESOURCE_REFERENCED.getCode()).isEqualTo("409003");
        assertThat(ErrorCode.SNAPSHOT_INVALID.getCode()).isEqualTo("400002");
        assertThat(ErrorCode.NOT_FOUND.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void carryCodeInException() {
        final WiresException ex = new WiresException(ErrorCode.TOPOLOGY_CYCLE, "cycle detected");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOPOLOGY_CYCLE);
        assertThat(ex.getMessage()).isEqualTo("cycle detected");

        final WiresException withCause = new WiresException(ErrorCode.ENGINE_ERROR, "boom",
                new IllegalStateException());
        assertThat(withCause.getErrorCode()).isEqualTo(ErrorCode.ENGINE_ERROR);
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }
}
