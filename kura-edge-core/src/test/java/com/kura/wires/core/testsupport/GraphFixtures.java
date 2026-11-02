package com.kura.wires.core.testsupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * 图模型测试夹具：快速构造组件配置、多端口连线与整图配置，
 * 属性名与快照 JSON 字段名均与上游 Kura 保持一致（Rules 08 §2）。
 */
public final class GraphFixtures {

    /** 默认测试工厂 PID */
    public static final String FACTORY_PID = "test.factory";

    private GraphFixtures() {
        // 工具类禁止实例化
    }

    /**
     * 构造组件配置（{@code configuration} 节点，携带 {@code service.factoryPid}）。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 工厂 PID
     * @return 组件配置
     */
    public static ComponentConfiguration componentConfiguration(final String pid, final String factoryPid) {
        final ComponentConfiguration configuration = new ComponentConfiguration();
        configuration.setPid(pid);
        final Map<String, Object> properties = new HashMap<>();
        properties.put(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid);
        configuration.setProperties(properties);
        return configuration;
    }

    /**
     * 构造默认端口数（1/1）的图内组件配置。
     *
     * @param pid 组件实例 PID
     * @return 图内组件配置
     */
    public static WireComponentConfiguration component(final String pid) {
        return component(pid, FACTORY_PID, 1, 1);
    }

    /**
     * 构造图内组件配置（{@code properties} 携带端口数与画布位置）。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 工厂 PID
     * @param inputPorts 输入端口数
     * @param outputPorts 输出端口数
     * @return 图内组件配置
     */
    public static WireComponentConfiguration component(final String pid, final String factoryPid, final int inputPorts,
            final int outputPorts) {
        final Map<String, Object> graphProperties = new HashMap<>();
        graphProperties.put(WireGraphConstants.INPUT_PORT_COUNT, inputPorts);
        graphProperties.put(WireGraphConstants.OUTPUT_PORT_COUNT, outputPorts);
        graphProperties.put(WireGraphConstants.POSITION_X, 10);
        graphProperties.put(WireGraphConstants.POSITION_Y, 20);
        return new WireComponentConfiguration(componentConfiguration(pid, factoryPid), graphProperties);
    }

    /**
     * 构造不带端口数属性的图内组件配置（触发工厂默认端口数回退）。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 工厂 PID
     * @return 图内组件配置
     */
    public static WireComponentConfiguration componentWithoutPortCounts(final String pid, final String factoryPid) {
        final Map<String, Object> graphProperties = new HashMap<>();
        graphProperties.put(WireGraphConstants.POSITION_X, 10);
        graphProperties.put(WireGraphConstants.POSITION_Y, 20);
        return new WireComponentConfiguration(componentConfiguration(pid, factoryPid), graphProperties);
    }

    /**
     * 构造 0 号端口到 0 号端口的连线。
     *
     * @param emitterPid 发射组件 PID
     * @param receiverPid 接收组件 PID
     * @return 多端口连线
     */
    public static MultiportWireConfiguration wire(final String emitterPid, final String receiverPid) {
        return new MultiportWireConfiguration(emitterPid, receiverPid, 0, 0);
    }

    /**
     * 构造整图配置。
     *
     * @param components 组件配置列表
     * @param wires 连线列表
     * @return 整图配置
     */
    public static WireGraphConfiguration graph(final List<WireComponentConfiguration> components,
            final List<MultiportWireConfiguration> wires) {
        return new WireGraphConfiguration(components, wires);
    }

    /**
     * 构造只有组件、没有连线的整图配置。
     *
     * @param components 组件配置列表
     * @return 整图配置
     */
    public static WireGraphConfiguration graphOf(final WireComponentConfiguration... components) {
        return graph(List.of(components), List.of());
    }
}
