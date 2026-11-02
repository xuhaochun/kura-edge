/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

/**
 * 数据接收端组件角色，对照上游 {@code org.eclipse.kura.wire.WireReceiver}。
 * 引擎按连线的 {@code receiverPort} 将 {@link WireEnvelope} 投递给
 * {@link #onWireReceive(WireEnvelope)}。
 */
public interface WireReceiver extends WireComponent {
    // 接收方法在 WireComponent#onWireReceive 中定义
}
