/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.exception;

/**
 * 统一业务错误码表：6 位 = HTTP 状态码 + 3 位业务序号（Rules 07）。
 * 设计对照上游 {@code org.eclipse.kura.KuraErrorCode} 思路。
 */
public enum ErrorCode {

    /** 参数校验失败（Bean Validation） */
    VALIDATION_FAILED("400001", 400),
    /** 快照 JSON 非法 / 缺少 WireGraph 属性 */
    SNAPSHOT_INVALID("400002", 400),
    /** 组件配置非法（缺 factoryPid / 类型不符） */
    CONFIG_INVALID("400003", 400),
    /** 拓扑校验失败：检测到环 */
    TOPOLOGY_CYCLE("409001", 409),
    /** 端口不匹配：emitter/receiver 端口超界或不兼容 */
    PORT_MISMATCH("409002", 409),
    /** 资源被引用不可删除（组件仍被图引用） */
    RESOURCE_REFERENCED("409003", 409),
    /** 组件/PID 不存在 */
    NOT_FOUND("404001", 404),
    /** 未认证 */
    UNAUTHORIZED("401001", 401),
    /** 无权限（缺 wires.admin 角色） */
    FORBIDDEN("403001", 403),
    /** 图引擎内部错误 */
    ENGINE_ERROR("500001", 500),
    /** 持久化错误 */
    PERSISTENCE_ERROR("500002", 500),
    /** 通道 IO / 驱动调用失败（资产读写） */
    CHANNEL_IO_FAILED("500003", 500),
    /** 更新包下载失败（RFC 006） */
    UPDATE_DOWNLOAD_FAILED("500004", 500),
    /** 非桌面运行时，不能热更新（RFC 006） */
    UPDATE_UNSUPPORTED("409004", 409),
    /** 已有更新在进行（RFC 006） */
    UPDATE_IN_PROGRESS("409005", 409),
    /** 更新包非法：版本号/URL/sha256 不合规（RFC 006） */
    UPDATE_REQUEST_INVALID("400004", 400);

    private final String code;
    private final int httpStatus;

    ErrorCode(final String code, final int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 返回 6 位业务错误码字符串。
     *
     * @return 错误码，如 {@code 409001}
     */
    public String getCode() {
        return this.code;
    }

    /**
     * 返回对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getHttpStatus() {
        return this.httpStatus;
    }
}
