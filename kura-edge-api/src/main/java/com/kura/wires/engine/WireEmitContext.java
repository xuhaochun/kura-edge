/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

import com.kura.wires.model.WireEnvelope;

/**
 * 组件发射上下文：引擎创建组件实例时注入，组件（Timer 等主动发射端）经此把
 * {@link WireEnvelope} 发射到下游，等价上游 WireAdmin 的 Producer 语义。
 */
@FunctionalInterface
public interface WireEmitContext {

    /**
     * 把信封沿图中本组件的所有出边（按 emitterPort 对应的连线）投递给接收端。
     * 引擎负责异步执行，组件不得在其中做长时间阻塞操作。
     *
     * @param envelope 待发射信封
     */
    void emit(WireEnvelope envelope);
}
