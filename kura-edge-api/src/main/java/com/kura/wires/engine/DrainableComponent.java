/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

/**
 * 需要异步「排空」的缓冲型接收组件（如 Fifo）：接收时只入队，由引擎在执行器上异步调用
 * {@link #drain()} 把已入队的信封发往下游。
 * <p>
 * 之所以由引擎调用而不是组件自建消费线程，是为了满足 Rules 08 §5「引擎提供异步执行器，
 * 禁止组件自建线程池」；实现方的 {@code drain()} 必须可并发调用（与接收路径共享队列时自行加锁）。
 * </p>
 */
public interface DrainableComponent {

    /**
     * 把当前已入队的信封按顺序全部发往下游；队列为空时不做任何事。
     */
    void drain();
}
