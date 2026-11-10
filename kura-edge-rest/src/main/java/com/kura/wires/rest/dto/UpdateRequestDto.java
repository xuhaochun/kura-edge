/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 热更新请求体（RFC 006 §2.4 / RFC 007）：{@code POST /api/v1/system/update}。
 * <p>
 * {@code url} 必填；{@code sha256} 与 {@code version} 可留空，此时由后端补齐——
 * {@code version} 从 URL 的文件名里取（{@code .../kura-edge-1.0.4.jar} → {@code 1.0.4}），
 * {@code sha256} 取实际下载到的摘要（只靠 HTTPS/内网信任，因此**强烈建议**填上）。
 * 界面上的"填一个网址就能升级"就是走这条补齐路径。
 * </p>
 * <p>
 * 非空时仍然先过 Bean Validation：{@code version} 决定落地文件名，必须是白名单字符（防路径穿越）、
 * 不许空串；{@code sha256} 必须是 64 位十六进制。
 * </p>
 */
public class UpdateRequestDto {

    /**
     * jar 下载地址；**留空表示用发布服务器上的最新版本**（RFC 010）——地址与 sha256 都由清单给出。
     * 非空时只接受 http/https。
     */
    @Pattern(regexp = "^$|^https?://\\S+$", message = "只允许 http/https 地址，或留空表示升级到发布服务器上的最新版")
    private String url;

    /** 期望的 sha256；留空表示"以实际下载到的摘要为准"（见类注释）。 */
    @Pattern(regexp = "^$|^[0-9a-fA-F]{64}$", message = "必须是 64 位十六进制，或留空")
    private String sha256;

    /** 目标版本号；留空表示"从 URL 文件名推导"，推导不出来则报 400004。 */
    @Pattern(regexp = "^$|^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$",
            message = "只能含字母、数字与 . _ -，且不超过 32 位，或留空")
    private String version;

    /** 供 Jackson 反序列化使用。 */
    public UpdateRequestDto() {
    }

    /**
     * 创建更新请求。
     *
     * @param url jar 下载地址（只允许 http/https）
     * @param sha256 期望的 sha256（64 位十六进制，可留空）
     * @param version 目标版本号（如 {@code 1.0.4}，可留空）
     */
    public UpdateRequestDto(final String url, final String sha256, final String version) {
        this.url = url;
        this.sha256 = sha256;
        this.version = version;
    }

    /**
     * 返回 jar 下载地址。
     *
     * @return 下载地址
     */
    public String getUrl() {
        return this.url;
    }

    /**
     * 设置 jar 下载地址。
     *
     * @param url 下载地址
     */
    public void setUrl(final String url) {
        this.url = url;
    }

    /**
     * 返回期望的 sha256。
     *
     * @return 64 位十六进制摘要
     */
    public String getSha256() {
        return this.sha256;
    }

    /**
     * 设置期望的 sha256。
     *
     * @param sha256 64 位十六进制摘要
     */
    public void setSha256(final String sha256) {
        this.sha256 = sha256;
    }

    /**
     * 返回目标版本号。
     *
     * @return 版本号
     */
    public String getVersion() {
        return this.version;
    }

    /**
     * 设置目标版本号。
     *
     * @param version 版本号
     */
    public void setVersion(final String version) {
        this.version = version;
    }
}
