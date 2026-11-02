/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel.listener;

import static java.util.Objects.requireNonNull;

import com.kura.wires.model.channel.ChannelRecord;

/**
 * 驱动通道事件，对照上游 {@code org.eclipse.kura.channel.listener.ChannelEvent}（不可变）。
 */
public final class ChannelEvent {

    private final ChannelRecord channelRecord;

    /**
     * 创建事件。
     *
     * @param channelRecord 通道记录
     */
    public ChannelEvent(final ChannelRecord channelRecord) {
        this.channelRecord = requireNonNull(channelRecord, "Channel record cannot be null");
    }

    /**
     * 返回事件携带的通道记录。
     *
     * @return 通道记录
     */
    public ChannelRecord getChannelRecord() {
        return this.channelRecord;
    }

    @Override
    public String toString() {
        return "ChannelEvent{channelRecord=" + this.channelRecord + "}";
    }
}
