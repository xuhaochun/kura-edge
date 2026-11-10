/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 组件日志行（SSE 事件负载）：对照 RFC 007「日志详情」面板的数据形态。
 * <p>
 * 与 {@code ComponentLogLine} 的差异：{@code timestamp} 以 ISO-8601 字符串出线，
 * 前端直接按本地时区格式化为 {@code HH:mm:ss.SSS}。
 * </p>
 */
public class ComponentLogLineDto {

    private long seq;

    private String timestamp;

    private String text;

    /** 供 Jackson 反序列化（测试与客户端）使用。 */
    public ComponentLogLineDto() {
    }

    /**
     * 创建日志行。
     *
     * @param seq 全局单调序号
     * @param timestamp ISO-8601 时间戳
     * @param text 日志文本
     */
    public ComponentLogLineDto(final long seq, final String timestamp, final String text) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.text = text;
    }

    /**
     * 返回全局单调序号。
     *
     * @return 序号
     */
    public long getSeq() {
        return this.seq;
    }

    /**
     * 设置全局单调序号。
     *
     * @param seq 序号
     */
    public void setSeq(final long seq) {
        this.seq = seq;
    }

    /**
     * 返回 ISO-8601 时间戳。
     *
     * @return 时间戳
     */
    public String getTimestamp() {
        return this.timestamp;
    }

    /**
     * 设置 ISO-8601 时间戳。
     *
     * @param timestamp 时间戳
     */
    public void setTimestamp(final String timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 返回日志文本。
     *
     * @return 日志文本
     */
    public String getText() {
        return this.text;
    }

    /**
     * 设置日志文本。
     *
     * @param text 日志文本
     */
    public void setText(final String text) {
        this.text = text;
    }
}
