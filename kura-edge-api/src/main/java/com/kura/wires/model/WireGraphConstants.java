/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

/**
 * 图内常量：属性名与 PID 必须与上游完全一致，保证 Kura 原生快照可导入（Rules 08 §2）。
 * 对照上游 {@code org.eclipse.kura.wire.graph.Constants} 与
 * {@code WireGraphServiceImpl} 中的常量。
 */
public final class WireGraphConstants {

    /** 快照中外层配置的服务 PID（对照 WireGraphServiceImpl.CONF_PID） */
    public static final String WIRE_GRAPH_SERVICE_PID = "org.eclipse.kura.wire.graph.WireGraphService";

    /** 图 JSON 所在的字符串属性名（对照 WireGraphServiceImpl.NEW_WIRE_GRAPH_PROPERTY） */
    public static final String WIRE_GRAPH_PROPERTY = "WireGraph";

    /** 组件配置中的工厂 PID 属性名（对照 KuraConstants SERVICE_FACTORYPID） */
    public static final String SERVICE_FACTORY_PID = "service.factoryPid";

    /** 组件属性：输入端口数（对照 WireGraphServiceImpl.INPUT_PORT_COUNT） */
    public static final String INPUT_PORT_COUNT = "inputPortCount";

    /** 组件属性：输出端口数（对照 WireGraphServiceImpl.OUTPUT_PORT_COUNT） */
    public static final String OUTPUT_PORT_COUNT = "outputPortCount";

    /** 渲染属性：画布 X 坐标（对照 DEFAULT_RENDERING_PROPERTIES） */
    public static final String POSITION_X = "position.x";

    /** 渲染属性：画布 Y 坐标 */
    public static final String POSITION_Y = "position.y";

    private WireGraphConstants() {
        // 常量类禁止实例化
    }
}
