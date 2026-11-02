/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.mapper;

import java.util.List;
import java.util.Map;

import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.rest.dto.WireGraphTopologyDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphDtoMapper} 的单元测试：拓扑字段名与上游 WireGraph JSON 一致
 * （components / wires / renderingProperties.position），多端口语义保留。
 */
class GraphDtoMapperTest {

    @Test
    void successfulMapOfComponentWithRenderingPropertiesAndMultiportWire() {
        final WireGraphConfiguration graph = givenGraphWithTimerAndLoggerConnected();

        final WireGraphTopologyDto dto = GraphDtoMapper.toDto(graph);

        assertThat(dto.getComponents()).hasSize(1);
        assertThat(dto.getComponents().get(0).getPid()).isEqualTo("timer-1");
        assertThat(dto.getComponents().get(0).getInputPortCount()).isZero();
        assertThat(dto.getComponents().get(0).getOutputPortCount()).isEqualTo(1);
        assertThat(dto.getComponents().get(0).getRenderingProperties().getPosition().getX()).isEqualTo(10.0f);
        assertThat(dto.getComponents().get(0).getRenderingProperties().getPosition().getY()).isEqualTo(20.5f);
        assertThat(dto.getComponents().get(0).getRenderingProperties().getInputPortNames()).containsEntry("0", "in");
        assertThat(dto.getWires()).hasSize(1);
        assertThat(dto.getWires().get(0).getEmitter()).isEqualTo("timer-1");
        assertThat(dto.getWires().get(0).getReceiver()).isEqualTo("logger-1");
        assertThat(dto.getWires().get(0).getReceiverPort()).isZero();
    }

    @Test
    void successfulMapKeepsPortsOfMultiportWire() {
        final WireGraphConfiguration graph = new WireGraphConfiguration(List.of(),
                List.of(new MultiportWireConfiguration("timer-1", "logger-1", 1, 2)));

        final WireGraphTopologyDto dto = GraphDtoMapper.toDto(graph);

        assertThat(dto.getWires().get(0).getEmitterPort()).isEqualTo(1);
        assertThat(dto.getWires().get(0).getReceiverPort()).isEqualTo(2);
    }

    @Test
    void successfulMapOfComponentWithoutGraphPropertiesLeavesRenderingValuesUnset() {
        final WireGraphConfiguration graph = new WireGraphConfiguration(List
                .of(new WireComponentConfiguration(new ComponentConfiguration("timer-1", null, Map.of()), Map.of())),
                List.of());

        final WireGraphTopologyDto dto = GraphDtoMapper.toDto(graph);

        assertThat(dto.getComponents().get(0).getInputPortCount()).isNull();
        assertThat(dto.getComponents().get(0).getRenderingProperties().getPosition().getX()).isNull();
        assertThat(dto.getComponents().get(0).getRenderingProperties().getInputPortNames()).isEmpty();
    }

    @Test
    void successfulMapOfEmptyGraph() {
        final WireGraphTopologyDto dto = GraphDtoMapper.toDto(new WireGraphConfiguration(List.of(), List.of()));

        assertThat(dto.getComponents()).isEmpty();
        assertThat(dto.getWires()).isEmpty();
    }

    private WireGraphConfiguration givenGraphWithTimerAndLoggerConnected() {
        final WireComponentConfiguration timer = new WireComponentConfiguration(
                new ComponentConfiguration("timer-1", null, Map.of("service.factoryPid", "org.eclipse.kura.wire.Timer")),
                Map.of("inputPortCount", 0, "outputPortCount", 1, "position.x", 10, "position.y", 20.5, "inputPortNames.0",
                        "in"));
        return new WireGraphConfiguration(List.of(timer),
                List.of(new MultiportWireConfiguration("timer-1", "logger-1", 0, 0)));
    }
}
