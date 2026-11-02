/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

/**
 * 多端口连线配置：连线标识为 {@code (emitterPid, emitterPort) -> (receiverPid, receiverPort)}，
 * 对照上游 {@code org.eclipse.kura.wire.graph.MultiportWireConfiguration}。
 * <p>
 * 端口约束（引擎校验，前端同样兜底）：{@code 0 <= emitterPort < outputPortCount}、
 * {@code 0 <= receiverPort < inputPortCount}。
 * </p>
 */
public class MultiportWireConfiguration extends WireConfiguration {

    private int emitterPort;
    private int receiverPort;

    /** 创建空连线（供反序列化使用）。 */
    public MultiportWireConfiguration() {
        super();
    }

    /**
     * 创建多端口连线。
     *
     * @param emitterPid 发射组件 PID
     * @param receiverPid 接收组件 PID
     * @param emitterPort 发射端口（从 0 开始）
     * @param receiverPort 接收端口（从 0 开始）
     */
    public MultiportWireConfiguration(final String emitterPid, final String receiverPid, final int emitterPort,
            final int receiverPort) {
        super(emitterPid, receiverPid);
        this.emitterPort = emitterPort;
        this.receiverPort = receiverPort;
    }

    public int getEmitterPort() {
        return this.emitterPort;
    }

    public void setEmitterPort(final int emitterPort) {
        this.emitterPort = emitterPort;
    }

    public int getReceiverPort() {
        return this.receiverPort;
    }

    public void setReceiverPort(final int receiverPort) {
        this.receiverPort = receiverPort;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final MultiportWireConfiguration other = (MultiportWireConfiguration) obj;
        return this.emitterPort == other.emitterPort && this.receiverPort == other.receiverPort;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + this.emitterPort;
        result = prime * result + this.receiverPort;
        return result;
    }
}
