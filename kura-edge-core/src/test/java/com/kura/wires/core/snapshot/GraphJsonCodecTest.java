/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * {@link GraphJsonCodec} 的行为测试：序列化格式与上游
 * {@code WireGraphJsonMarshallUnmarshallImpl} 逐字段一致（components / wires /
 * renderingProperties），保证 Kura 原生快照可双向导入导出。
 */
public class GraphJsonCodecTest {

    /** 上游导出的 WireGraph 属性 JSON（kura-wires 的 marshaller 输出形态，手工抄录）。 */
    private static final String UPSTREAM_GRAPH_JSON = "{\"components\":[{\"pid\":\"timer\",\"inputPortCount\":1,"
            + "\"outputPortCount\":1,\"renderingProperties\":{\"position\":{\"x\":12.5,\"y\":7.0},"
            + "\"inputPortNames\":{\"0\":\"in\"},\"outputPortNames\":{\"0\":\"out\"}}}],"
            + "\"wires\":[{\"emitter\":\"timer\",\"emitterPort\":0,\"receiver\":\"logger\",\"receiverPort\":1}]}";

    private GraphJsonCodec codec;
    private WireGraphConfiguration graph;

    @BeforeEach
    public void setUp() {
        this.codec = new GraphJsonCodec();
    }

    @Test
    public void successfulMarshalUnmarshalRoundTrip() {
        givenGraphWithTwoComponentsAndOneWire();

        final String json = this.codec.marshal(this.graph);
        final WireGraphConfiguration restored = this.codec.unmarshal(json);

        assertThat(restored.getWireComponentConfigurations()).hasSize(2);
        assertThat(restored.getWireConfigurations()).containsExactlyElementsOf(this.graph.getWireConfigurations());
        assertThat(restored.getWireComponentConfigurations().get(0).getConfiguration().getPid()).isEqualTo("timer");
        final Map<String, Object> firstGraphProps = restored.getWireComponentConfigurations().get(0).getProperties();
        assertThat(firstGraphProps).containsEntry(WireGraphConstants.OUTPUT_PORT_COUNT, 1)
                .containsEntry(WireGraphConstants.POSITION_X, 12.5f)
                .containsEntry(WireGraphConstants.POSITION_Y, 7.0f).containsEntry("inputPortNames.0", "in");
    }

    @Test
    public void successfulMarshalUsesUpstreamWireGraphFieldNames() {
        givenGraphWithTwoComponentsAndOneWire();

        final String json = this.codec.marshal(this.graph);

        assertThat(json).contains("\"components\"").contains("\"wires\"").contains("\"pid\"").contains("\"emitter\"")
                .contains("\"emitterPort\"").contains("\"receiver\"").contains("\"receiverPort\"")
                .contains("\"renderingProperties\"").contains("\"position\"").contains("\"inputPortNames\"")
                .contains("\"outputPortNames\"");
    }

    /**
     * 图 JSON 是兼容契约：它只带拓扑与渲染属性，组件配置属性（含密码类属性）绝不能出现，
     * 否则上游导入时会被当成图属性的同名键误读。
     */
    @Test
    public void successfulMarshalOmitsComponentConfigurationProperties() {
        final ComponentConfiguration configuration = new ComponentConfiguration("logger", null,
                Map.of(WireGraphConstants.SERVICE_FACTORY_PID, "org.eclipse.kura.wire.Logger", "password",
                        "s3cr3t", "topic", "t"));
        final WireGraphConfiguration withConfigProperties = new WireGraphConfiguration(
                List.of(new WireComponentConfiguration(configuration, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1))),
                List.of());

        final String json = this.codec.marshal(withConfigProperties);

        assertThat(json).doesNotContain(WireGraphConstants.SERVICE_FACTORY_PID).doesNotContain("s3cr3t")
                .doesNotContain("topic").doesNotContain("\"wireComponentConfigurations\"")
                .doesNotContain("\"wireConfigurations\"");
    }

    @Test
    public void successfulUnmarshalOfUpstreamExportedSnapshot() {
        final WireGraphConfiguration restored = this.codec.unmarshal(UPSTREAM_GRAPH_JSON);

        assertThat(restored.getWireComponentConfigurations()).hasSize(1);
        final WireComponentConfiguration component = restored.getWireComponentConfigurations().get(0);
        assertThat(component.getConfiguration().getPid()).isEqualTo("timer");
        assertThat(component.getProperties()).containsEntry(WireGraphConstants.INPUT_PORT_COUNT, 1)
                .containsEntry(WireGraphConstants.OUTPUT_PORT_COUNT, 1)
                .containsEntry(WireGraphConstants.POSITION_X, 12.5f)
                .containsEntry(WireGraphConstants.POSITION_Y, 7.0f).containsEntry("inputPortNames.0", "in")
                .containsEntry("outputPortNames.0", "out");
        assertThat(restored.getWireConfigurations()).containsExactly(new MultiportWireConfiguration("timer", "logger",
                0, 1));
    }

    /** Kura 原生快照的组件条目可以只有 pid：端口数与渲染属性缺省，不报错。 */
    @Test
    public void successfulUnmarshalOfComponentWithoutGraphProperties() {
        final WireGraphConfiguration restored = this.codec
                .unmarshal("{\"components\":[{\"pid\":\"timer\"}],\"wires\":[]}");

        assertThat(restored.getWireComponentConfigurations().get(0).getProperties()).isEmpty();
        assertThat(restored.getWireConfigurations()).isEmpty();
    }

    @Test
    public void successfulUnmarshalSkipsComponentsWithoutPidAndWiresWithoutEndpoints() {
        final String json = "{\"components\":[{\"inputPortCount\":1},{\"pid\":\"timer\"}],"
                + "\"wires\":[{\"emitterPort\":0},{\"emitter\":\"timer\",\"receiver\":\"logger\"}]}";

        final WireGraphConfiguration restored = this.codec.unmarshal(json);

        assertThat(restored.getWireComponentConfigurations()).hasSize(1);
        assertThat(restored.getWireComponentConfigurations().get(0).getConfiguration().getPid()).isEqualTo("timer");
        assertThat(restored.getWireConfigurations())
                .containsExactly(new MultiportWireConfiguration("timer", "logger", 0, 0));
    }

    /** 上游对顶层键用 equalsIgnoreCase，故大小写不同的图 JSON 也必须能解析。 */
    @Test
    public void successfulUnmarshalOfGraphWithCaseInsensitiveTopLevelKeys() {
        final WireGraphConfiguration restored = this.codec
                .unmarshal("{\"Components\":[{\"pid\":\"timer\",\"OutputPortCount\":1}],\"WIRES\":[]}");

        assertThat(restored.getWireComponentConfigurations()).hasSize(1);
        assertThat(restored.getWireComponentConfigurations().get(0).getConfiguration().getPid()).isEqualTo("timer");
        assertThat(restored.getWireConfigurations()).isEmpty();
    }

    @Test
    public void successfulMarshalOfEmptyGraph() {
        final WireGraphConfiguration empty = new WireGraphConfiguration(List.of(), List.of());

        final WireGraphConfiguration restored = this.codec.unmarshal(this.codec.marshal(empty));

        assertThat(restored.getWireComponentConfigurations()).isEmpty();
        assertThat(restored.getWireConfigurations()).isEmpty();
    }

    @Test
    public void rejectUnmarshalOnInvalidJson() {
        assertThatThrownBy(() -> this.codec.unmarshal("{not json")).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_INVALID);
    }

    @Test
    public void rejectUnmarshalOnJsonThatIsNotAnObject() {
        assertThatThrownBy(() -> this.codec.unmarshal("[1,2,3]")).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_INVALID);
    }

    private void givenGraphWithTwoComponentsAndOneWire() {
        final ComponentConfiguration timerConfig = new ComponentConfiguration("timer", null, Map
                .of(WireGraphConstants.SERVICE_FACTORY_PID, "org.eclipse.kura.wire.Timer"));
        final ComponentConfiguration loggerConfig = new ComponentConfiguration("logger", null, Map
                .of(WireGraphConstants.SERVICE_FACTORY_PID, "org.eclipse.kura.wire.Logger"));
        final WireComponentConfiguration timer = new WireComponentConfiguration(timerConfig, Map
                .of(WireGraphConstants.INPUT_PORT_COUNT, 1, WireGraphConstants.OUTPUT_PORT_COUNT, 1,
                        WireGraphConstants.POSITION_X, 12.5, WireGraphConstants.POSITION_Y, 7.0, "inputPortNames.0",
                        "in"));
        final WireComponentConfiguration logger = new WireComponentConfiguration(loggerConfig, Map
                .of(WireGraphConstants.INPUT_PORT_COUNT, 1, WireGraphConstants.OUTPUT_PORT_COUNT, 0));
        final MultiportWireConfiguration wire = new MultiportWireConfiguration("timer", "logger", 0, 0);
        this.graph = new WireGraphConfiguration(List.of(timer, logger), List.of(wire));
    }
}
