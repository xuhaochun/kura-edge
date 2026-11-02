/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

import java.util.Map;

import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ocd;

/**
 * 组件工厂 SPI：components 模块每个内置组件各提供一个实现，注册到 core 的
 * 组件注册表（对照上游 OSGi DS 注册 + WireComponentTrackerCustomizer 的发现机制）。
 * <p>
 * 注册表据此回答「有哪些组件工厂」「某 factoryPid 的默认端口数与定义」，
 * 并按需创建/销毁实例。
 * </p>
 */
public interface WireComponentFactory {

    /**
     * 返回组件工厂 PID（如 {@code org.eclipse.kura.wire.Timer}），与上游保持一致。
     *
     * @return 工厂 PID
     */
    String factoryPid();

    /**
     * 返回默认输入端口数（写入组件实例的 {@code inputPortCount} 属性）。
     *
     * @return 默认输入端口数
     */
    int defaultInputPortCount();

    /**
     * 返回默认输出端口数（写入组件实例的 {@code outputPortCount} 属性）。
     *
     * @return 默认输出端口数
     */
    int defaultOutputPortCount();

    /**
     * 返回组件配置描述对象（REST {@code GET /metadata} 的数据源之一）。
     *
     * @return OCD
     */
    Ocd getDefinition();

    /**
     * 创建组件实例。
     *
     * @param pid 组件实例 PID
     * @param properties 组件配置属性（已合并默认值）
     * @param emitContext 引擎注入的发射上下文
     * @return 组件实例
     */
    WireComponent create(String pid, Map<String, Object> properties, WireEmitContext emitContext);
}
