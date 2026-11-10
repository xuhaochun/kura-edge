/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 更新受理回执（RFC 006 §2.4）：{@code POST /api/v1/system/update} 返回 202，
 * {@code data} 为该版本将被安装到的文件名与摘要。
 * <p>
 * 回执只代表「已下载并校验、已写 {@code staging/pending.json}」；真正换 jar 与重启 JVM 由桌面外壳
 * 在 1.5 s 后完成，结果写在外壳的 {@code app/apply-result.json} 与安装日志里。
 * </p>
 */
public class UpdateAcceptedDto {

    private String version;

    private String jar;

    private String sha256;

    public UpdateAcceptedDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建受理回执。
     *
     * @param version 目标版本号
     * @param jar 落地的文件名（相对 {@code staging/} 与 {@code app/}）
     * @param sha256 该 jar 的 sha256
     */
    public UpdateAcceptedDto(final String version, final String jar, final String sha256) {
        this.version = version;
        this.jar = jar;
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

    /**
     * 返回落地的文件名。
     *
     * @return 文件名（如 {@code kura-edge-1.0.4.jar}）
     */
    public String getJar() {
        return this.jar;
    }

    /**
     * 设置落地的文件名。
     *
     * @param jar 文件名
     */
    public void setJar(final String jar) {
        this.jar = jar;
    }

    /**
     * 返回该 jar 的 sha256。
     *
     * @return 64 位十六进制摘要
     */
    public String getSha256() {
        return this.sha256;
    }

    /**
     * 设置该 jar 的 sha256。
     *
     * @param sha256 64 位十六进制摘要
     */
    public void setSha256(final String sha256) {
        this.sha256 = sha256;
    }
}
