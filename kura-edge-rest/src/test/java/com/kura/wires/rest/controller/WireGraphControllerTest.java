/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.List;
import java.util.Map;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.PropertyDto;
import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.WireGraphSnapshotService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WireGraphController} 的 Web 层测试：正常 200 / 校验 400 / 领域异常映射 /
 * 401 / 403。
 */
@WebMvcTest(WireGraphController.class)
@Import(SecurityConfig.class)
class WireGraphControllerTest {

    private static final String SNAPSHOT_BODY = """
            {"configs":[{"pid":"timer-1","properties":{"service.factoryPid":{"type":"STRING",
            "value":"org.eclipse.kura.wire.Timer"}}}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WireGraphSnapshotService snapshotService;

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetTopology() throws Exception {
        // 图属性（端口数、位置）在 WireComponentConfiguration.properties 里，
        // 组件配置属性在 configuration.properties 里（两组不可混同，对照上游 marshalComponentProperties）
        final WireGraphConfiguration graph = new WireGraphConfiguration(
                List.of(new WireComponentConfiguration(
                        new ComponentConfiguration("timer-1", null,
                                Map.of("service.factoryPid", "org.eclipse.kura.wire.Timer")),
                        Map.of("inputPortCount", 0, "outputPortCount", 1, "position.x", 10.0f, "position.y", 20.0f))),
                List.of(new MultiportWireConfiguration("timer-1", "logger-1", 0, 0)));
        givenGraph(graph);

        this.mockMvc.perform(get("/api/v1/wires/graph/topology")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.components[0].pid").value("timer-1"))
                .andExpect(jsonPath("$.data.components[0].inputPortCount").value(0))
                .andExpect(jsonPath("$.data.components[0].outputPortCount").value(1))
                .andExpect(jsonPath("$.data.components[0].renderingProperties.position.x").value(10.0))
                .andExpect(jsonPath("$.data.wires[0].emitter").value("timer-1"))
                .andExpect(jsonPath("$.data.wires[0].emitterPort").value(0))
                .andExpect(jsonPath("$.data.wires[0].receiver").value("logger-1"))
                .andExpect(jsonPath("$.data.wires[0].receiverPort").value(0))
                // 上游契约：图 JSON 不含组件配置属性，也不含旧字段名
                .andExpect(jsonPath("$.data.components[0].configuration").doesNotExist())
                .andExpect(jsonPath("$.data.wireComponentConfigurations").doesNotExist())
                .andExpect(jsonPath("$.data.wireConfigurations").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetSnapshot() throws Exception {
        when(this.snapshotService.getSnapshot()).thenReturn(List.of(new ComponentConfigurationDto("timer-1", null,
                Map.of("password", new PropertyDto("PASSWORD", "PASSWORD")))));

        this.mockMvc.perform(get("/api/v1/wires/graph/snapshot")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].pid").value("timer-1"))
                .andExpect(jsonPath("$.data[0].properties.password.value").value("PASSWORD"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulUpdateSnapshot() throws Exception {
        this.mockMvc
                .perform(put("/api/v1/wires/graph/snapshot").contentType(MediaType.APPLICATION_JSON)
                        .content(SNAPSHOT_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(this.snapshotService).updateSnapshot(ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulDeleteGraph() throws Exception {
        this.mockMvc.perform(delete("/api/v1/wires/graph")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(this.snapshotService).deleteGraph();
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void domainExceptionIsMappedToHttpStatus() throws Exception {
        givenGraphError(new WiresException(ErrorCode.NOT_FOUND, "snapshot missing"));

        this.mockMvc.perform(get("/api/v1/wires/graph/topology")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001)).andExpect(jsonPath("$.message").value("snapshot missing"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void invalidSnapshotBodyIsRejected() throws Exception {
        this.mockMvc
                .perform(put("/api/v1/wires/graph/snapshot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configs\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        this.mockMvc.perform(get("/api/v1/wires/graph/topology")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401001));
    }

    @Test
    @WithMockUser(roles = "user")
    void missingAdminRoleIsForbidden() throws Exception {
        this.mockMvc.perform(get("/api/v1/wires/graph/topology")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403001));
    }

    private void givenGraph(final WireGraphConfiguration graph) throws Exception {
        when(this.snapshotService.getGraph()).thenReturn(graph);
    }

    private void givenGraphError(final WiresException exception) throws Exception {
        when(this.snapshotService.getGraph()).thenThrow(exception);
    }
}
