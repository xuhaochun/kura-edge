/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

/**
 * 通道读写结果标记，对照上游 {@code org.eclipse.kura.channel.ChannelFlag}。
 */
public enum ChannelFlag {

    /** 失败 */
    FAILURE,
    /** 成功 */
    SUCCESS
}
