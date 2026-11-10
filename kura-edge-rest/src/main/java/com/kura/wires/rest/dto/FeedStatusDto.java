/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 自动更新（清单轮询）的当前状态（RFC 008）：{@code GET /api/v1/system/feed}。
 * <p>
 * 现场排障最常问的两个问题——"自动更新开着吗"和"上次为什么没升"——都在这一个响应里回答：
 * {@code enabled}/{@code url}/{@code intervalMs} 说配置，{@code lastCheckAt}/{@code lastError}
 * 说最近一次轮询，{@code lastStagedVersion} 说已经提交给外壳的版本。
 * </p>
 */
public class FeedStatusDto {

    private boolean enabled;

    private String url;

    private String serverUrl;

    private long intervalMs;

    private String lastCheckAt;

    private boolean lastCheckOk;

    private String lastError;

    private String latestSeenVersion;

    private String lastStagedVersion;

    private String lastStagedAt;

    private String failedVersion;

    private String failedAt;

    /** 供 Jackson 序列化使用。 */
    public FeedStatusDto() {
    }

    /**
     * 返回是否启用了自动更新。
     *
     * @return true 表示已配置清单地址并会定时轮询
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * 设置是否启用自动更新。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回清单地址。
     *
     * @return 绝对地址，未配置时为空串
     */
    public String getUrl() {
        return this.url;
    }

    /**
     * 设置清单地址。
     *
     * @param url 清单地址
     */
    public void setUrl(final String url) {
        this.url = url;
    }

    /**
     * 返回发布服务器地址（配置项 {@code kura.update.release-server-url}）。
     *
     * @return 地址，未配置时为空串
     */
    public String getServerUrl() {
        return this.serverUrl;
    }

    /**
     * 设置发布服务器地址。
     *
     * @param serverUrl 地址
     */
    public void setServerUrl(final String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * 返回轮询间隔。
     *
     * @return 毫秒
     */
    public long getIntervalMs() {
        return this.intervalMs;
    }

    /**
     * 设置轮询间隔。
     *
     * @param intervalMs 毫秒
     */
    public void setIntervalMs(final long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * 返回最近一次检查时间。
     *
     * @return ISO-8601 时间串，从未检查时为空串
     */
    public String getLastCheckAt() {
        return this.lastCheckAt;
    }

    /**
     * 设置最近一次检查时间。
     *
     * @param lastCheckAt ISO-8601 时间串
     */
    public void setLastCheckAt(final String lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    /**
     * 返回最近一次检查是否成功。
     *
     * @return true 表示清单拉取并解析成功
     */
    public boolean isLastCheckOk() {
        return this.lastCheckOk;
    }

    /**
     * 设置最近一次检查是否成功。
     *
     * @param lastCheckOk 是否成功
     */
    public void setLastCheckOk(final boolean lastCheckOk) {
        this.lastCheckOk = lastCheckOk;
    }

    /**
     * 返回最近一次失败原因。
     *
     * @return 失败原因，成功时为空串
     */
    public String getLastError() {
        return this.lastError;
    }

    /**
     * 设置最近一次失败原因。
     *
     * @param lastError 失败原因
     */
    public void setLastError(final String lastError) {
        this.lastError = lastError;
    }

    /**
     * 返回清单里看到的版本。
     *
     * @return 版本号，未看到时为空串
     */
    public String getLatestSeenVersion() {
        return this.latestSeenVersion;
    }

    /**
     * 设置清单里看到的版本。
     *
     * @param latestSeenVersion 版本号
     */
    public void setLatestSeenVersion(final String latestSeenVersion) {
        this.latestSeenVersion = latestSeenVersion;
    }

    /**
     * 返回已提交给外壳的版本。
     *
     * @return 版本号，未提交过时为空串
     */
    public String getLastStagedVersion() {
        return this.lastStagedVersion;
    }

    /**
     * 设置已提交给外壳的版本。
     *
     * @param lastStagedVersion 版本号
     */
    public void setLastStagedVersion(final String lastStagedVersion) {
        this.lastStagedVersion = lastStagedVersion;
    }

    /**
     * 返回最近一次提交时间。
     *
     * @return ISO-8601 时间串，未提交过时为空串
     */
    public String getLastStagedAt() {
        return this.lastStagedAt;
    }

    /**
     * 设置最近一次提交时间。
     *
     * @param lastStagedAt ISO-8601 时间串
     */
    public void setLastStagedAt(final String lastStagedAt) {
        this.lastStagedAt = lastStagedAt;
    }

    /**
     * 返回最近失败的版本。
     *
     * @return 版本号，没有失败记录时为空串
     */
    public String getFailedVersion() {
        return this.failedVersion;
    }

    /**
     * 设置最近失败的版本。
     *
     * @param failedVersion 版本号
     */
    public void setFailedVersion(final String failedVersion) {
        this.failedVersion = failedVersion;
    }

    /**
     * 返回最近失败时间。
     *
     * @return ISO-8601 时间串，没有失败记录时为空串
     */
    public String getFailedAt() {
        return this.failedAt;
    }

    /**
     * 设置最近失败时间。
     *
     * @param failedAt ISO-8601 时间串
     */
    public void setFailedAt(final String failedAt) {
        this.failedAt = failedAt;
    }
}
