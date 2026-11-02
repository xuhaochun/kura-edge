/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

/**
 * 通道读写状态，对照上游 {@code org.eclipse.kura.channel.ChannelStatus}（不可变）。
 */
public final class ChannelStatus {

    private final ChannelFlag channelFlag;

    private final String exceptionMessage;

    private final Exception exception;

    /**
     * 创建只带标记的状态。
     *
     * @param channelFlag 状态标记
     */
    public ChannelStatus(final ChannelFlag channelFlag) {
        this(channelFlag, null, null);
    }

    /**
     * 创建完整状态。
     *
     * @param channelFlag 状态标记
     * @param exceptionMessage 异常消息（成功时为 null）
     * @param exception 异常（成功时为 null）
     */
    public ChannelStatus(final ChannelFlag channelFlag, final String exceptionMessage, final Exception exception) {
        this.channelFlag = requireNonNull(channelFlag, "Channel Flag cannot be null");
        this.exceptionMessage = exceptionMessage;
        this.exception = exception;
    }

    /**
     * 是否成功。
     *
     * @return 成功时为 true
     */
    public boolean isSuccess() {
        return ChannelFlag.SUCCESS == this.channelFlag;
    }

    public ChannelFlag getChannelFlag() {
        return this.channelFlag;
    }

    public String getExceptionMessage() {
        return this.exceptionMessage;
    }

    public Exception getException() {
        return this.exception;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof final ChannelStatus other)) {
            return false;
        }
        return this.channelFlag == other.channelFlag && Objects.equals(this.exceptionMessage, other.exceptionMessage)
                && Objects.equals(this.exception == null ? null : this.exception.toString(),
                        other.exception == null ? null : other.exception.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.channelFlag, this.exceptionMessage);
    }

    @Override
    public String toString() {
        return "ChannelStatus{" + this.channelFlag + ", " + this.exceptionMessage + "}";
    }
}
