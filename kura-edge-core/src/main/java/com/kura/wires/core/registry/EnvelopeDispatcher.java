/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.registry;

import com.kura.wires.model.WireEnvelope;

/**
 * 信封分发回调：组件注册表创建组件实例时把它注入 {@code WireEmitContext}，
 * 由图引擎实现（对照上游 WireAdmin 把 Producer 的发射转交 Wire 的语义）。
 * <p>
 * 实现方必须异步投递且不得因单个组件异常中断整图运行（Rules 08 §5）。
 * </p>
 */
@FunctionalInterface
public interface EnvelopeDispatcher {

    /**
     * 把信封沿图中 {@code emitterPid} 的所有出边投递给接收端组件。
     *
     * @param emitterPid 发射组件 PID
     * @param envelope 待投递信封
     */
    void dispatch(String emitterPid, WireEnvelope envelope);
}
