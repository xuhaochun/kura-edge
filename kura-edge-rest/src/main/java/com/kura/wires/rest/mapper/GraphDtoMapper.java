/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.rest.dto.GraphComponentDto;
import com.kura.wires.rest.dto.GraphComponentRenderingDto;
import com.kura.wires.rest.dto.GraphPositionDto;
import com.kura.wires.rest.dto.GraphWireDto;
import com.kura.wires.rest.dto.WireGraphTopologyDto;

/**
 * 领域图模型 ⇄ 拓扑 DTO 的手写映射（禁 MapStruct，Rules 05 §4）。
 * <p>
 * 输出结构与上游 {@code WireGraphJsonMarshallUnmarshallImpl} 的图 JSON 一致：
 * 组件条目为 {@code {pid, inputPortCount, outputPortCount, renderingProperties}}，
 * 渲染属性里位置是 {@code position.{x,y}}、端口名是 {@code inputPortNames.<port>} /
 * {@code outputPortNames.<port>}；连线为 {@code {emitter, emitterPort, receiver, receiverPort}}。
 * 组件自身的配置属性不属于图 JSON（由图快照的独立配置条目承载）。
 * </p>
 */
public final class GraphDtoMapper {

    /** 端口名图属性的分隔前缀形式：{@code inputPortNames.<port>}（对照上游 INPUT_PORT_NAMES_KEY） */
    private static final String INPUT_PORT_NAMES_PREFIX = "inputPortNames.";

    /** 端口名图属性的分隔前缀形式：{@code outputPortNames.<port>}（对照上游 OUTPUT_PORT_NAMES_KEY） */
    private static final String OUTPUT_PORT_NAMES_PREFIX = "outputPortNames.";

    private GraphDtoMapper() {
        // 工具类禁止实例化
    }

    /**
     * 领域整图配置转拓扑 DTO（GET /graph/topology 输出）。
     *
     * @param graph 领域整图配置
     * @return 拓扑 DTO
     */
    public static WireGraphTopologyDto toDto(final WireGraphConfiguration graph) {
        final List<GraphComponentDto> components = new ArrayList<>();
        for (final WireComponentConfiguration component : graph.getWireComponentConfigurations()) {
            components.add(toDto(component));
        }
        final List<GraphWireDto> wires = new ArrayList<>();
        for (final MultiportWireConfiguration wire : graph.getWireConfigurations()) {
            wires.add(toDto(wire));
        }
        return new WireGraphTopologyDto(components, wires);
    }

    private static GraphComponentDto toDto(final WireComponentConfiguration component) {
        final Map<String, Object> properties = component.getProperties() == null ? Map.of() : component.getProperties();
        return new GraphComponentDto(component.getConfiguration() == null ? null : component.getConfiguration().getPid(),
                intValue(properties.get(WireGraphConstants.INPUT_PORT_COUNT)),
                intValue(properties.get(WireGraphConstants.OUTPUT_PORT_COUNT)),
                new GraphComponentRenderingDto(
                        positionOf(properties.get(WireGraphConstants.POSITION_X),
                                properties.get(WireGraphConstants.POSITION_Y)),
                        portNames(properties, INPUT_PORT_NAMES_PREFIX),
                        portNames(properties, OUTPUT_PORT_NAMES_PREFIX)));
    }

    /** 端口名在图属性里是 {@code <prefix>.<port>}，在 JSON 里是 {@code {<port>: <name>}} 对象。 */
    private static Map<String, String> portNames(final Map<String, Object> properties, final String prefix) {
        final Map<String, String> names = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : properties.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof final String name) {
                names.put(entry.getKey().substring(prefix.length()), name);
            }
        }
        return names;
    }

    private static GraphPositionDto positionOf(final Object x, final Object y) {
        return new GraphPositionDto(floatValue(x), floatValue(y));
    }

    private static GraphWireDto toDto(final MultiportWireConfiguration wire) {
        return new GraphWireDto(wire.getEmitterPid(), wire.getEmitterPort(), wire.getReceiverPid(),
                wire.getReceiverPort());
    }

    private static Integer intValue(final Object value) {
        return value instanceof final Number number ? Integer.valueOf(number.intValue()) : null;
    }

    private static Float floatValue(final Object value) {
        return value instanceof final Number number ? Float.valueOf(number.floatValue()) : null;
    }
}
