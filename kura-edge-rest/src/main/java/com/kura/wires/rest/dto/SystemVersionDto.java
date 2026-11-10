/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 当前后端版本信息（RFC 006 §2.4）：{@code GET /api/v1/system/version}。
 * <p>
 * 桌面运行时读外壳写的 {@code app/current.json}，因此 {@code source=desktop}；
 * 用 {@code java -jar} / {@code mvn spring-boot:run} 启动时没有该文件，返回 {@code source=maven}，
 * 版本号取自 jar 清单的 {@code Implementation-Version}（开发环境可能缺失，回退 {@code dev}）。
 * </p>
 */
public class SystemVersionDto {

    private String version;

    private String jar;

    private String sha256;

    private String source;

    private String role;

    private boolean blueGreen;

    public SystemVersionDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建版本信息（不含蓝绿角色，等价于 {@code role=leader}、{@code blueGreen=false}）。
     *
     * @param version 版本号
     * @param jar 当前运行的 jar 绝对路径（未知时为空串）
     * @param sha256 当前 jar 的 sha256（未知时为空串）
     * @param source 版本来源：{@code desktop} 或 {@code maven}
     */
    public SystemVersionDto(final String version, final String jar, final String sha256, final String source) {
        this(version, jar, sha256, source, "leader", false);
    }

    /**
     * 创建版本信息（含蓝绿角色）。
     *
     * @param version 版本号
     * @param jar 当前运行的 jar 绝对路径（未知时为空串）
     * @param sha256 当前 jar 的 sha256（未知时为空串）
     * @param source 版本来源：{@code desktop} 或 {@code maven}
     * @param role 图执行角色：{@code leader}（正在跑图）或 {@code standby}（已就绪但未跑图）
     * @param blueGreen 是否具备蓝绿能力（PostgreSQL 咨询锁可用）
     */
    public SystemVersionDto(final String version, final String jar, final String sha256, final String source,
            final String role, final boolean blueGreen) {
        this.version = version;
        this.jar = jar;
        this.sha256 = sha256;
        this.source = source;
        this.role = role;
        this.blueGreen = blueGreen;
    }

    /**
     * 返回版本号。
     *
     * @return 版本号
     */
    public String getVersion() {
        return this.version;
    }

    /**
     * 设置版本号。
     *
     * @param version 版本号
     */
    public void setVersion(final String version) {
        this.version = version;
    }

    /**
     * 返回当前运行的 jar 路径。
     *
     * @return 绝对路径，未知时为空串
     */
    public String getJar() {
        return this.jar;
    }

    /**
     * 设置当前运行的 jar 路径。
     *
     * @param jar 绝对路径
     */
    public void setJar(final String jar) {
        this.jar = jar;
    }

    /**
     * 返回当前 jar 的 sha256。
     *
     * @return 64 位十六进制摘要，未知时为空串
     */
    public String getSha256() {
        return this.sha256;
    }

    /**
     * 设置当前 jar 的 sha256。
     *
     * @param sha256 64 位十六进制摘要
     */
    public void setSha256(final String sha256) {
        this.sha256 = sha256;
    }

    /**
     * 返回版本来源。
     *
     * @return {@code desktop} 或 {@code maven}
     */
    public String getSource() {
        return this.source;
    }

    /**
     * 设置版本来源。
     *
     * @param source {@code desktop} 或 {@code maven}
     */
    public void setSource(final String source) {
        this.source = source;
    }

    /**
     * 返回图执行角色。
     *
     * @return {@code leader} 或 {@code standby}
     */
    public String getRole() {
        return this.role;
    }

    /**
     * 设置图执行角色。
     *
     * @param role {@code leader} 或 {@code standby}
     */
    public void setRole(final String role) {
        this.role = role;
    }

    /**
     * 返回是否具备蓝绿能力。
     *
     * @return true 表示热更新可以零中断（PostgreSQL 咨询锁可用）
     */
    public boolean isBlueGreen() {
        return this.blueGreen;
    }

    /**
     * 设置是否具备蓝绿能力。
     *
     * @param blueGreen 是否具备蓝绿能力
     */
    public void setBlueGreen(final boolean blueGreen) {
        this.blueGreen = blueGreen;
    }
}
