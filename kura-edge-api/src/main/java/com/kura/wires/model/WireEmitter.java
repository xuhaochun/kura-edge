/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

/**
 * 数据发射端组件角色，对照上游 {@code org.eclipse.kura.wire.WireEmitter}。
 * <p>
 * 发射通过创建组件实例时引擎注入的
 * {@link com.kura.wires.engine.WireEmitContext} 完成（等价于上游
 * WireAdmin Producer 语义），例如 Timer 到点后主动发射。
 * </p>
 */
public interface WireEmitter extends WireComponent {
    // 发射行为经由 WireEmitContext 完成，见接口 Javadoc
}
