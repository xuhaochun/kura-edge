/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * 整图 JSON 编解码器：手写 Jackson 映射，替换上游基于 OSGi 服务查找的
 * {@code Marshaller} / {@code Unmarshaller}（对照
 * {@code WireGraphServiceImpl#marshal} / {@code #unmarshal}）。
 * <p>
 * 序列化格式与上游 {@code WireGraphJsonMarshallUnmarshallImpl} 完全一致——它是
 * WireGraphService 配置里 {@code WireGraph} 字符串属性的内容，是导入 Kura 原生快照的兼容契约：
 *
 * <pre>
 * {
 *   "components": [
 *     { "pid": "timer", "inputPortCount": 1, "outputPortCount": 1,
 *       "renderingProperties": { "position": { "x": 12.5, "y": 7.0 },
 *                                "inputPortNames": { "0": "in" }, "outputPortNames": { "0": "out" } } }
 *   ],
 *   "wires": [ { "emitter": "timer", "emitterPort": 0, "receiver": "logger", "receiverPort": 0 } ]
 * }
 * </pre>
 *
 * 与上游一致，图 JSON <b>只</b>携带拓扑与渲染属性：组件自身的配置属性（如 Logger 的 topic、
 * service.factoryPid）由 ConfigurationAdmin/本项目的 {@code ComponentConfigurationService}
 * 单独保存，不写进图 JSON（上游 {@code WireGraphServiceImpl#get} 在读取时再回填）。
 * </p>
 * <p>
 * 反序列化沿用上游的宽容策略：缺 {@code inputPortCount}/{@code outputPortCount}/
 * {@code renderingProperties} 的组件条目照常解析（端口数由调用方按工厂默认值回落），
 * 缺 {@code pid} 的组件条目与缺 {@code emitter}/{@code receiver} 的连线直接跳过。
 * </p>
 */
public class GraphJsonCodec {

    /** 图 JSON 顶层键：组件列表（对照上游 COMPONENTS_KEY） */
    private static final String COMPONENTS_KEY = "components";

    /** 图 JSON 顶层键：连线列表（对照上游 WIRES_KEY） */
    private static final String WIRES_KEY = "wires";

    /** 组件条目键：PID（对照上游 PID_KEY） */
    private static final String PID_KEY = "pid";

    /** 组件条目键：渲染属性容器（对照上游 RENDERING_PROPERTIES_KEY） */
    private static final String RENDERING_PROPERTIES_KEY = "renderingProperties";

    /** 渲染属性键：画布位置（对照上游 POSITION_KEY） */
    private static final String POSITION_KEY = "position";

    /** 位置键：X（对照上游 marshalPosition） */
    private static final String X_KEY = "x";

    /** 位置键：Y（对照上游 marshalPosition） */
    private static final String Y_KEY = "y";

    /** 渲染属性键：输入端口名（对照上游 INPUT_PORT_NAMES_KEY） */
    private static final String INPUT_PORT_NAMES_KEY = "inputPortNames";

    /** 渲染属性键：输出端口名（对照上游 OUTPUT_PORT_NAMES_KEY） */
    private static final String OUTPUT_PORT_NAMES_KEY = "outputPortNames";

    /** 连线键：发射组件 PID（对照上游 EMITTER_PID_KEY） */
    private static final String EMITTER_KEY = "emitter";

    /** 连线键：发射端口（对照上游 EMITTER_PORT_KEY） */
    private static final String EMITTER_PORT_KEY = "emitterPort";

    /** 连线键：接收组件 PID（对照上游 RECEIVER_PID_KEY） */
    private static final String RECEIVER_KEY = "receiver";

    /** 连线键：接收端口（对照上游 RECEIVER_PORT_KEY） */
    private static final String RECEIVER_PORT_KEY = "receiverPort";

    private static final Logger log = LoggerFactory.getLogger(GraphJsonCodec.class);

    private final ObjectMapper mapper;

    /** 使用默认 {@link ObjectMapper} 创建编解码器。 */
    public GraphJsonCodec() {
        this(new ObjectMapper());
    }

    /**
     * 使用指定 {@link ObjectMapper} 创建编解码器（测试可注入定制配置）。
     *
     * @param objectMapper JSON 映射器
     */
    public GraphJsonCodec(final ObjectMapper objectMapper) {
        this.mapper = objectMapper;
    }

    /**
     * 把整图配置序列化为上游格式的 JSON。
     *
     * @param configuration 整图配置
     * @return JSON 字符串
     * @throws WiresException 序列化失败时（ENGINE_ERROR，对照上游 marshal 失败返回 null 的场景）
     */
    public String marshal(final WireGraphConfiguration configuration) {
        try {
            final ObjectNode graph = this.mapper.createObjectNode();
            final ArrayNode components = graph.putArray(COMPONENTS_KEY);
            for (final WireComponentConfiguration component : configuration.getWireComponentConfigurations()) {
                components.add(marshalComponent(component));
            }
            final ArrayNode wires = graph.putArray(WIRES_KEY);
            for (final MultiportWireConfiguration wire : configuration.getWireConfigurations()) {
                final ObjectNode node = wires.addObject();
                node.put(EMITTER_KEY, wire.getEmitterPid());
                node.put(EMITTER_PORT_KEY, wire.getEmitterPort());
                node.put(RECEIVER_KEY, wire.getReceiverPid());
                node.put(RECEIVER_PORT_KEY, wire.getReceiverPort());
            }
            return this.mapper.writeValueAsString(graph);
        } catch (final Exception e) {
            throw new WiresException(ErrorCode.ENGINE_ERROR, "Failed to marshal wire graph configuration", e);
        }
    }

    /**
     * 把上游格式的图 JSON 反序列化为整图配置。
     *
     * @param json 图 JSON
     * @return 整图配置（组件配置只带 PID；配置属性由调用方从配置服务回填）
     * @throws WiresException JSON 非法时（SNAPSHOT_INVALID，对照上游 DECODER_ERROR）
     */
    public WireGraphConfiguration unmarshal(final String json) {
        try {
            final JsonNode root = this.mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new WiresException(ErrorCode.SNAPSHOT_INVALID, "Invalid wire graph JSON");
            }
            return new WireGraphConfiguration(unmarshalComponents(root), unmarshalWires(root));
        } catch (final WiresException e) {
            throw e;
        } catch (final Exception e) {
            log.warn("Failed to unmarshal wire graph configuration");
            throw new WiresException(ErrorCode.SNAPSHOT_INVALID, "Invalid wire graph JSON", e);
        }
    }

    private ObjectNode marshalComponent(final WireComponentConfiguration component) {
        final ObjectNode node = this.mapper.createObjectNode();
        node.put(PID_KEY, component.getConfiguration() == null ? null : component.getConfiguration().getPid());

        final Map<String, Object> properties = component.getProperties();
        putIntIfPresent(node, WireGraphConstants.INPUT_PORT_COUNT, properties.get(WireGraphConstants.INPUT_PORT_COUNT));
        putIntIfPresent(node, WireGraphConstants.OUTPUT_PORT_COUNT, properties.get(WireGraphConstants.OUTPUT_PORT_COUNT));

        final ObjectNode rendering = node.putObject(RENDERING_PROPERTIES_KEY);
        final ObjectNode position = rendering.putObject(POSITION_KEY);
        putFloatIfPresent(position, X_KEY, properties.get(WireGraphConstants.POSITION_X));
        putFloatIfPresent(position, Y_KEY, properties.get(WireGraphConstants.POSITION_Y));
        marshalPortNames(rendering, INPUT_PORT_NAMES_KEY, properties);
        marshalPortNames(rendering, OUTPUT_PORT_NAMES_KEY, properties);
        return node;
    }

    /** 端口名在图属性里是 {@code inputPortNames.<port>} 形式，在 JSON 里提升为嵌套对象。 */
    private void marshalPortNames(final ObjectNode rendering, final String key, final Map<String, Object> properties) {
        final ObjectNode names = rendering.putObject(key);
        final String prefix = key + ".";
        for (final Map.Entry<String, Object> entry : properties.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof final String name) {
                names.put(entry.getKey().substring(prefix.length()), name);
            }
        }
    }

    private static void putIntIfPresent(final ObjectNode node, final String key, final Object value) {
        if (value instanceof final Number number) {
            node.put(key, number.intValue());
        }
    }

    private static void putFloatIfPresent(final ObjectNode node, final String key, final Object value) {
        if (value instanceof final Number number) {
            node.put(key, number.floatValue());
        }
    }

    private List<WireComponentConfiguration> unmarshalComponents(final JsonNode root) {
        final List<WireComponentConfiguration> components = new ArrayList<>();
        final JsonNode array = topLevelNode(root, COMPONENTS_KEY);
        if (array == null || !array.isArray()) {
            return components;
        }
        for (final JsonNode element : array) {
            if (!element.isObject()) {
                continue;
            }
            final JsonNode pid = element.get(PID_KEY);
            // 上游同样跳过缺 pid 的条目（WireGraphJsonMarshallUnmarshallImpl#unmarshalWireComponentConfiguration）
            if (pid == null || !pid.isTextual()) {
                continue;
            }
            components.add(new WireComponentConfiguration(new ComponentConfiguration(pid.asText(), null, null),
                    unmarshalGraphProperties(element)));
        }
        return components;
    }

    private Map<String, Object> unmarshalGraphProperties(final JsonNode element) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        putIntIfNumber(properties, WireGraphConstants.INPUT_PORT_COUNT, element.get(WireGraphConstants.INPUT_PORT_COUNT));
        putIntIfNumber(properties, WireGraphConstants.OUTPUT_PORT_COUNT,
                element.get(WireGraphConstants.OUTPUT_PORT_COUNT));

        final JsonNode rendering = element.get(RENDERING_PROPERTIES_KEY);
        if (rendering != null && rendering.isObject()) {
            final JsonNode position = rendering.get(POSITION_KEY);
            if (position != null && position.isObject()) {
                putFloatIfNumber(properties, WireGraphConstants.POSITION_X, position.get(X_KEY));
                putFloatIfNumber(properties, WireGraphConstants.POSITION_Y, position.get(Y_KEY));
            }
            readPortNames(rendering.get(INPUT_PORT_NAMES_KEY), INPUT_PORT_NAMES_KEY, properties);
            readPortNames(rendering.get(OUTPUT_PORT_NAMES_KEY), OUTPUT_PORT_NAMES_KEY, properties);
        }
        return properties;
    }

    private static void readPortNames(final JsonNode names, final String key, final Map<String, Object> properties) {
        if (names == null || !names.isObject()) {
            return;
        }
        final Iterator<Map.Entry<String, JsonNode>> iterator = names.fields();
        while (iterator.hasNext()) {
            final Map.Entry<String, JsonNode> entry = iterator.next();
            if (entry.getValue().isTextual()) {
                properties.put(key + "." + entry.getKey(), entry.getValue().asText());
            }
        }
    }

    private static void putIntIfNumber(final Map<String, Object> properties, final String key, final JsonNode value) {
        if (value != null && value.isNumber()) {
            properties.put(key, Integer.valueOf(value.intValue()));
        }
    }

    private static void putFloatIfNumber(final Map<String, Object> properties, final String key, final JsonNode value) {
        if (value != null && value.isNumber()) {
            properties.put(key, Float.valueOf(value.floatValue()));
        }
    }

    private List<MultiportWireConfiguration> unmarshalWires(final JsonNode root) {
        final List<MultiportWireConfiguration> wires = new ArrayList<>();
        final JsonNode array = topLevelNode(root, WIRES_KEY);
        if (array == null || !array.isArray()) {
            return wires;
        }
        for (final JsonNode element : array) {
            if (!element.isObject()) {
                continue;
            }
            final JsonNode emitter = element.get(EMITTER_KEY);
            final JsonNode receiver = element.get(RECEIVER_KEY);
            // 上游同样跳过缺 emitter/receiver 的连线（unmarshalWireConfiguration）
            if (emitter == null || !emitter.isTextual() || receiver == null || !receiver.isTextual()) {
                continue;
            }
            wires.add(new MultiportWireConfiguration(emitter.asText(), receiver.asText(),
                    portOf(element.get(EMITTER_PORT_KEY)), portOf(element.get(RECEIVER_PORT_KEY))));
        }
        return wires;
    }

    /**
     * 取顶层节点：上游用 {@code equalsIgnoreCase} 匹配 {@code components}/{@code wires}
     * （{@code WireGraphJsonMarshallUnmarshallImpl#unmarshalToWireGraphConfiguration}），
     * 这里保持同样的宽容度，精确匹配优先。
     *
     * @param root 图 JSON 根节点
     * @param key 目标键（小写形式）
     * @return 命中的节点；无命中时为 null
     */
    private static JsonNode topLevelNode(final JsonNode root, final String key) {
        final JsonNode exact = root.get(key);
        if (exact != null) {
            return exact;
        }
        final Iterator<Map.Entry<String, JsonNode>> iterator = root.fields();
        while (iterator.hasNext()) {
            final Map.Entry<String, JsonNode> entry = iterator.next();
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static int portOf(final JsonNode value) {
        return value != null && value.isNumber() ? value.intValue() : 0;
    }
}
