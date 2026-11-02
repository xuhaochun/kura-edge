/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.List;
import java.util.Map;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.PropertyDto;
import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.WireConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WireConfigController} 的 Web 层测试：正常 200 / 校验 400 / 领域异常映射 /
 * 401 / 403。
 */
@WebMvcTest(WireConfigController.class)
@Import(SecurityConfig.class)
class WireConfigControllerTest {

    private static final String PID_SET_BODY = "{\"pids\":[\"timer-1\"]}";
    private static final String CONFIGS_BODY = """
            {"configs":[{"pid":"timer-1","properties":{"service.factoryPid":{"type":"STRING",
            "value":"org.eclipse.kura.wire.Timer"}}}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WireConfigService configService;

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetConfigsByPids() throws Exception {
        when(this.configService.getConfigsByPids(ArgumentMatchers.any()))
                .thenReturn(List.of(new ComponentConfigurationDto("timer-1", null,
                        Map.of("service.factoryPid", new PropertyDto("STRING", "org.eclipse.kura.wire.Timer"),
                                "password", new PropertyDto("PASSWORD", "PASSWORD")))));

        this.mockMvc
                .perform(post("/api/v1/wires/configs/by-pids").contentType(MediaType.APPLICATION_JSON)
                        .content(PID_SET_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].pid").value("timer-1"))
                .andExpect(jsonPath("$.data[0].properties.password.value").value("PASSWORD"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulDeleteConfigsByPids() throws Exception {
        this.mockMvc
                .perform(delete("/api/v1/wires/configs/by-pids").contentType(MediaType.APPLICATION_JSON)
                        .content(PID_SET_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(this.configService).deleteConfigsByPids(ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void referencedPidDeletionIsRejected() throws Exception {
        doThrow(new WiresException(ErrorCode.RESOURCE_REFERENCED, "\"timer-1\" is referenced by the Wire Graph"))
                .when(this.configService).deleteConfigsByPids(ArgumentMatchers.any());

        this.mockMvc
                .perform(delete("/api/v1/wires/configs/by-pids").contentType(MediaType.APPLICATION_JSON)
                        .content(PID_SET_BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409003));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulUpdateConfigs() throws Exception {
        this.mockMvc
                .perform(put("/api/v1/wires/configs").contentType(MediaType.APPLICATION_JSON).content(CONFIGS_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(this.configService).updateConfigs(ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void invalidPidSetIsRejected() throws Exception {
        this.mockMvc
                .perform(post("/api/v1/wires/configs/by-pids").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pids\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void invalidConfigListIsRejected() throws Exception {
        this.mockMvc
                .perform(put("/api/v1/wires/configs").contentType(MediaType.APPLICATION_JSON).content("{\"configs\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void unauthenticatedConfigRequestIsUnauthorized() throws Exception {
        this.mockMvc.perform(post("/api/v1/wires/configs/by-pids").contentType(MediaType.APPLICATION_JSON)
                .content(PID_SET_BODY)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "user")
    void configRequestWithoutAdminRoleIsForbidden() throws Exception {
        this.mockMvc.perform(delete("/api/v1/wires/configs/by-pids").contentType(MediaType.APPLICATION_JSON)
                .content(PID_SET_BODY)).andExpect(status().isForbidden());
    }
}
