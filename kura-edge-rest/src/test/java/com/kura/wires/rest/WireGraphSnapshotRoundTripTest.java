/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 整图快照的端到端往返测试（真实 Spring 上下文 + H2 + Flyway + 图引擎，不替换任何实现）：
 * 用上游 Kura 原生形状的 {@code WireGraph} JSON 保存整图，再读回校验字段名、端口、位置与
 * 组件配置属性，最后删除整图。这是「Kura 原生快照可导入」这条硬需求的验收测试
 * （对照上游 {@code WireRestService#updateWireGraphConfiguration} / {@code #getWireGraphConfiguration}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "wires.admin")
class WireGraphSnapshotRoundTripTest {

    /** 上游 Kura 导出的图 JSON（components / wires / renderingProperties，手工抄录自上游 marshaller 输出）。 */
    private static final String KURA_NATIVE_GRAPH_JSON = "{\"components\":["
            + "{\"pid\":\"timer1\",\"inputPortCount\":0,\"outputPortCount\":1,"
            + "\"renderingProperties\":{\"position\":{\"x\":40.0,\"y\":60.0},\"inputPortNames\":{},"
            + "\"outputPortNames\":{}}},"
            + "{\"pid\":\"logger1\",\"inputPortCount\":1,\"outputPortCount\":0,"
            + "\"renderingProperties\":{\"position\":{\"x\":240.0,\"y\":60.0},\"inputPortNames\":{},"
            + "\"outputPortNames\":{}}}],"
            + "\"wires\":[{\"emitter\":\"timer1\",\"emitterPort\":0,\"receiver\":\"logger1\",\"receiverPort\":0}]}";

    private static final String TIMER_PID = "org.eclipse.kura.wire.Timer";
    private static final String LOGGER_PID = "org.eclipse.kura.wire.Logger";
    private static final String GRAPH_SERVICE_PID = "org.eclipse.kura.wire.graph.WireGraphService";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() throws Exception {
        this.mockMvc.perform(delete("/api/v1/wires/graph"));
    }

    @Test
    void successfulRoundTripOfKuraNativeSnapshot() throws Exception {
        whenSavingKuraNativeSnapshot();
        thenSnapshotIsStoredInUpstreamShape();
        thenTopologyUsesUpstreamFieldNames();
    }

    @Test
    void successfulDeleteLeavesEmptyGraph() throws Exception {
        whenSavingKuraNativeSnapshot();

        this.mockMvc.perform(delete("/api/v1/wires/graph")).andExpect(status().isOk());

        final JsonNode graph = givenGraphJsonFromSnapshot();
        assertThat(graph.get("components")).isEmpty();
        assertThat(graph.get("wires")).isEmpty();
    }

    @Test
    void rejectSnapshotWithoutWireGraphProperty() throws Exception {
        final String body = "{\"configs\":[{\"pid\":\"" + GRAPH_SERVICE_PID
                + "\",\"properties\":{\"other\":{\"type\":\"STRING\",\"value\":\"x\"}}}]}";

        this.mockMvc.perform(put("/api/v1/wires/graph/snapshot").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.CONFIG_INVALID.getCode())));
    }

    @Test
    void rejectSnapshotWithUnknownFactoryPid() throws Exception {
        final String body = "{\"configs\":["
                + "{\"pid\":\"" + GRAPH_SERVICE_PID + "\",\"properties\":{\"WireGraph\":{\"type\":\"STRING\",\"value\":"
                + quote(KURA_NATIVE_GRAPH_JSON) + "}}},"
                + "{\"pid\":\"timer1\",\"properties\":{\"service.factoryPid\":{\"type\":\"STRING\",\"value\":\""
                + "org.eclipse.kura.wire.Unknown\"}}}]}";

        this.mockMvc.perform(put("/api/v1/wires/graph/snapshot").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.CONFIG_INVALID.getCode())));
    }

    private void whenSavingKuraNativeSnapshot() throws Exception {
        this.mockMvc
                .perform(put("/api/v1/wires/graph/snapshot").contentType(MediaType.APPLICATION_JSON)
                        .content(kuraNativeSnapshotBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }

    private void thenSnapshotIsStoredInUpstreamShape() throws Exception {
        final MvcResult result = this.mockMvc.perform(get("/api/v1/wires/graph/snapshot"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].pid").value(GRAPH_SERVICE_PID))
                .andExpect(jsonPath("$.data[0].properties.WireGraph.type").value("STRING")).andReturn();

        final JsonNode snapshot = this.mapper.readTree(result.getResponse().getContentAsString()).get("data");
        final String graphJson = snapshot.get(0).get("properties").get("WireGraph").get("value").asText();

        // 字段名契约：components / wires / renderingProperties，且不含旧字段名与组件配置属性
        assertThat(graphJson).contains("\"components\"").contains("\"wires\"").contains("\"renderingProperties\"")
                .doesNotContain("wireComponentConfigurations").doesNotContain("wireConfigurations")
                .doesNotContain("service.factoryPid").doesNotContain("simple.interval");

        final JsonNode graph = this.mapper.readTree(graphJson);
        assertThat(graph.get("components")).hasSize(2);
        final JsonNode timerComponent = graph.get("components").get(0);
        assertThat(timerComponent.get("pid").asText()).isEqualTo("timer1");
        assertThat(timerComponent.get("outputPortCount").asInt()).isEqualTo(1);
        assertThat(timerComponent.get("renderingProperties").get("position").get("x").asDouble()).isEqualTo(40.0d);
        final JsonNode wire = graph.get("wires").get(0);
        assertThat(wire.get("emitter").asText()).isEqualTo("timer1");
        assertThat(wire.get("receiver").asText()).isEqualTo("logger1");
        assertThat(wire.get("receiverPort").asInt()).isZero();

        // 组件配置属性走独立配置条目（含 factoryPid 与各自的配置项）
        final JsonNode timerConfig = findConfig(snapshot, "timer1");
        assertThat(timerConfig.get("properties").get("service.factoryPid").get("value").asText()).isEqualTo(TIMER_PID);
        assertThat(timerConfig.get("properties").get("simple.interval").get("value").asInt()).isEqualTo(3600);
        assertThat(findConfig(snapshot, "logger1").get("properties").get("log.verbosity").get("value").asText())
                .isEqualTo("QUIET");
    }

    private void thenTopologyUsesUpstreamFieldNames() throws Exception {
        final MvcResult result = this.mockMvc.perform(get("/api/v1/wires/graph/topology")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components[1].pid").value("logger1"))
                .andExpect(jsonPath("$.data.wires[0].emitter").value("timer1")).andReturn();

        final JsonNode data = this.mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.has("wireComponentConfigurations")).isFalse();
        assertThat(data.get("components").get(1).get("renderingProperties").get("position").get("y").asDouble())
                .isEqualTo(60.0d);
    }

    private JsonNode givenGraphJsonFromSnapshot() throws Exception {
        final MvcResult result = this.mockMvc.perform(get("/api/v1/wires/graph/snapshot")).andExpect(status().isOk())
                .andReturn();
        final JsonNode snapshot = this.mapper.readTree(result.getResponse().getContentAsString()).get("data");
        return this.mapper.readTree(snapshot.get(0).get("properties").get("WireGraph").get("value").asText());
    }

    private static JsonNode findConfig(final JsonNode snapshot, final String pid) {
        for (final JsonNode config : snapshot) {
            if (pid.equals(config.get("pid").asText())) {
                return config;
            }
        }
        throw new AssertionError("component configuration not found for " + pid);
    }

    private static String kuraNativeSnapshotBody() {
        return "{\"configs\":["
                + "{\"pid\":\"" + GRAPH_SERVICE_PID + "\",\"properties\":{\"WireGraph\":{\"type\":\"STRING\",\"value\":"
                + quote(KURA_NATIVE_GRAPH_JSON) + "}}},"
                + "{\"pid\":\"timer1\",\"properties\":{\"service.factoryPid\":{\"type\":\"STRING\",\"value\":\""
                + TIMER_PID + "\"},\"type\":{\"type\":\"STRING\",\"value\":\"SIMPLE\"},"
                + "\"simple.interval\":{\"type\":\"INTEGER\",\"value\":3600}}},"
                + "{\"pid\":\"logger1\",\"properties\":{\"service.factoryPid\":{\"type\":\"STRING\",\"value\":\""
                + LOGGER_PID + "\"},\"log.verbosity\":{\"type\":\"STRING\",\"value\":\"QUIET\"}}}]}";
    }

    private static String quote(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
