/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * 多端口连线校验器：对整图每条 {@link MultiportWireConfiguration} 校验
 * <ul>
 * <li>emitterPid / receiverPid 都必须出现在图的组件列表中；</li>
 * <li>{@code emitterPort ∈ [0, outputPortCount)}、{@code receiverPort ∈ [0, inputPortCount)}
 * ——端口语义与上游一致，从 0 开始（对照 {@code WireGraphServiceImpl} 与
 * {@code MultiportWireConfiguration} 的端口约束）；</li>
 * <li>端口数取自组件 properties 的 {@code inputPortCount}/{@code outputPortCount}，
 * 缺失时回退到工厂默认值；两者都不可用时视为图非法。</li>
 * </ul>
 * 任一约束违反抛 {@link WiresException}（{@link ErrorCode#PORT_MISMATCH}）。
 */
public class PortValidator {

    private final WireComponentRegistry registry;

    /**
     * 创建端口校验器。
     *
     * @param registry 组件注册表（用于按 factoryPid 查询工厂默认端口数）
     */
    public PortValidator(final WireComponentRegistry registry) {
        this.registry = registry;
    }

    /**
     * 校验整图全部连线。
     *
     * @param configuration 整图配置
     * @throws WiresException 存在端口不匹配的连线时（PORT_MISMATCH）
     */
    public void validate(final WireGraphConfiguration configuration) {
        final Map<String, WireComponentConfiguration> componentsByPid = new LinkedHashMap<>();
        for (final WireComponentConfiguration component : configuration.getWireComponentConfigurations()) {
            componentsByPid.put(component.getConfiguration().getPid(), component);
        }
        for (final MultiportWireConfiguration wire : configuration.getWireConfigurations()) {
            validateWire(wire, componentsByPid);
        }
    }

    private void validateWire(final MultiportWireConfiguration wire,
            final Map<String, WireComponentConfiguration> componentsByPid) {
        final WireComponentConfiguration emitter = requireComponent(componentsByPid, wire.getEmitterPid(), "emitter");
        final WireComponentConfiguration receiver = requireComponent(componentsByPid, wire.getReceiverPid(), "receiver");

        final int outputPortCount = resolvePortCount(emitter, WireGraphConstants.OUTPUT_PORT_COUNT, true);
        final int inputPortCount = resolvePortCount(receiver, WireGraphConstants.INPUT_PORT_COUNT, false);

        validatePort(wire.getEmitterPid(), "emitterPort", wire.getEmitterPort(), outputPortCount);
        validatePort(wire.getReceiverPid(), "receiverPort", wire.getReceiverPort(), inputPortCount);
    }

    private WireComponentConfiguration requireComponent(final Map<String, WireComponentConfiguration> componentsByPid,
            final String pid, final String role) {
        final WireComponentConfiguration component = pid == null ? null : componentsByPid.get(pid);
        if (component == null) {
            throw new WiresException(ErrorCode.PORT_MISMATCH,
                    role + " component " + pid + " of a wire is not part of the graph");
        }
        return component;
    }

    private int resolvePortCount(final WireComponentConfiguration component, final String propertyKey,
            final boolean outputPort) {
        final Object rawCount = component.getProperties().get(propertyKey);
        if (rawCount instanceof Number) {
            return ((Number) rawCount).intValue();
        }
        return defaultPortCount(component, outputPort);
    }

    private int defaultPortCount(final WireComponentConfiguration component, final boolean outputPort) {
        final String factoryPid = resolveFactoryPid(component);
        final WireComponentFactory factory = this.registry.findFactory(factoryPid).orElseThrow(
                () -> new WiresException(ErrorCode.PORT_MISMATCH,
                        "Cannot determine port counts of component " + component.getConfiguration().getPid()
                                + " : no port count properties and no factory " + factoryPid + " registered"));
        return outputPort ? factory.defaultOutputPortCount() : factory.defaultInputPortCount();
    }

    private String resolveFactoryPid(final WireComponentConfiguration component) {
        final Object configured = component.getConfiguration().getProperties()
                .get(WireGraphConstants.SERVICE_FACTORY_PID);
        if (configured instanceof String) {
            return (String) configured;
        }
        return this.registry.getFactoryPidOf(component.getConfiguration().getPid())
                .orElseThrow(() -> new WiresException(ErrorCode.PORT_MISMATCH, "Component "
                        + component.getConfiguration().getPid() + " has no " + WireGraphConstants.SERVICE_FACTORY_PID
                        + " property and no running instance"));
    }

    private static void validatePort(final String pid, final String portName, final int port, final int portCount) {
        if (port < 0 || port >= portCount) {
            throw new WiresException(ErrorCode.PORT_MISMATCH, portName + " " + port + " of component " + pid
                    + " is out of range [0, " + portCount + ")");
        }
    }
}
