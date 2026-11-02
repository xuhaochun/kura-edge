/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 拓扑中的连线 DTO，对照上游 WireGraph JSON 的 {@code wires[]} 项：
 * {@code {emitter, emitterPort, receiver, receiverPort}}。
 * <p>
 * 键名是上游契约（{@code emitter} / {@code receiver}，不是 emitterPid / receiverPid），
 * 禁止改名（Rules 05 §9）。
 * </p>
 */
public class GraphWireDto {

    private String emitter;
    private int emitterPort;
    private String receiver;
    private int receiverPort;

    public GraphWireDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建连线 DTO。
     *
     * @param emitter 发射组件 PID
     * @param emitterPort 发射端口（从 0 开始）
     * @param receiver 接收组件 PID
     * @param receiverPort 接收端口（从 0 开始）
     */
    public GraphWireDto(final String emitter, final int emitterPort, final String receiver, final int receiverPort) {
        this.emitter = emitter;
        this.emitterPort = emitterPort;
        this.receiver = receiver;
        this.receiverPort = receiverPort;
    }

    public String getEmitter() {
        return this.emitter;
    }

    public int getEmitterPort() {
        return this.emitterPort;
    }

    public String getReceiver() {
        return this.receiver;
    }

    public int getReceiverPort() {
        return this.receiverPort;
    }
}
