/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

/**
 * Wire 组件 SPI：图中可被连线的最小单元，对照上游
 * {@code org.eclipse.kura.wire.WireComponent}。
 * <p>
 * 组件实例由 {@code kura-edge-core} 的组件注册表根据
 * {@link com.kura.wires.engine.WireComponentFactory} 创建，生命周期由图引擎管理；
 * 组件内部禁止自建线程池与访问 HTTP/数据库（Rules 08 §5）。
 * </p>
 */
public interface WireComponent {

    /**
     * 返回组件实例 PID（kura.service.pid 语义）。
     *
     * @return 组件 PID
     */
    String getPid();

    /**
     * 接收到上游 {@link WireEnvelope} 时触发（接收端组件实现）。
     * 不应长时间阻塞（引擎提供异步执行器）。
     *
     * @param envelope 收到的信封
     */
    default void onWireReceive(final WireEnvelope envelope) {
        // 仅接收端组件需要实现
    }
}
