/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel.listener;

/**
 * 驱动通道监听器，对照上游 {@code org.eclipse.kura.channel.listener.ChannelListener}。
 * <p>
 * 本轮资产层<b>不注册</b>监听器（{@code +listen} 只存不跑，见 RFC 002 §6），接口保留以便后续启用。
 * </p>
 */
@FunctionalInterface
public interface ChannelListener {

    /**
     * 通道事件回调。
     *
     * @param event 事件
     */
    void onChannelEvent(ChannelEvent event);
}
