/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

/**
 * 两个 Wire 组件之间的一条连线，对照上游 {@code org.eclipse.kura.wire.WireConfiguration}。
 */
public class WireConfiguration {

    private String emitterPid;
    private String receiverPid;

    /** 创建空连线（供反序列化使用）。 */
    public WireConfiguration() {
        // Jackson 反序列化用
    }

    /**
     * 创建连线。
     *
     * @param emitterPid 发射组件 PID
     * @param receiverPid 接收组件 PID
     */
    public WireConfiguration(final String emitterPid, final String receiverPid) {
        this.emitterPid = requireNonNull(emitterPid, "Emitter PID cannot be null");
        this.receiverPid = requireNonNull(receiverPid, "Receiver PID cannot be null");
    }

    public String getEmitterPid() {
        return this.emitterPid;
    }

    public void setEmitterPid(final String emitterPid) {
        this.emitterPid = emitterPid;
    }

    public String getReceiverPid() {
        return this.receiverPid;
    }

    public void setReceiverPid(final String receiverPid) {
        this.receiverPid = receiverPid;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final WireConfiguration other = (WireConfiguration) obj;
        return Objects.equals(this.emitterPid, other.emitterPid) && Objects.equals(this.receiverPid, other.receiverPid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.emitterPid, this.receiverPid);
    }
}
