/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.rest.dto.PidAndFactoryPidDto;
import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.DriversAssetsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WireDriversAssetsController} 的 Web 层测试：列表、创建、删除与引用冲突的错误码。
 */
@WebMvcTest(WireDriversAssetsController.class)
@Import(SecurityConfig.class)
class WireDriversAssetsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DriversAssetsService service;

    @Test
    @WithMockUser(roles = "wires.admin")
    void listDriversAndAssets() throws Exception {
        when(this.service.listDrivers()).thenReturn(List.of(new PidAndFactoryPidDto("driver-1", "test.driver")));
        when(this.service.listAssets())
                .thenReturn(List.of(new PidAndFactoryPidDto("asset-1", "org.eclipse.kura.wire.WireAsset")));

        this.mockMvc.perform(get("/api/v1/wires/drivers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components[0].pid").value("driver-1"));
        this.mockMvc.perform(get("/api/v1/wires/assets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components[0].factoryPid").value("org.eclipse.kura.wire.WireAsset"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void createDriver() throws Exception {
        this.mockMvc.perform(post("/api/v1/wires/drivers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"factoryPid\":\"org.eclipse.kura.driver.simulated\",\"pid\":\"driver-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(this.service).createDriver(any());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void rejectCreateDriverWithoutFactoryPid() throws Exception {
        this.mockMvc.perform(post("/api/v1/wires/drivers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pid\":\"driver-1\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.VALIDATION_FAILED.getCode())));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void createAsset() throws Exception {
        this.mockMvc.perform(post("/api/v1/wires/assets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pid\":\"asset-1\",\"driverPid\":\"driver-1\",\"description\":\"demo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(this.service).createAsset(any());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void rejectDeleteDriverReferencedByAsset() throws Exception {
        doThrow(new WiresException(ErrorCode.RESOURCE_REFERENCED, "driver in use"))
                .when(this.service).deleteDriver(eq("driver-1"));

        this.mockMvc.perform(delete("/api/v1/wires/drivers/driver-1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.RESOURCE_REFERENCED.getCode())));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void rejectDeleteAssetReferencedByGraph() throws Exception {
        doThrow(new WiresException(ErrorCode.RESOURCE_REFERENCED, "asset in graph"))
                .when(this.service).deleteAsset(eq("asset-1"));

        this.mockMvc.perform(delete("/api/v1/wires/assets/asset-1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.RESOURCE_REFERENCED.getCode())));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void returnNotFoundWhenAssetConfigurationIsMissing() throws Exception {
        when(this.service.getAssetProperties("missing"))
                .thenThrow(new WiresException(ErrorCode.NOT_FOUND, "missing"));

        this.mockMvc.perform(get("/api/v1/wires/assets/missing/configuration")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.NOT_FOUND.getCode())));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void returnAssetConfiguration() throws Exception {
        when(this.service.getAssetProperties("asset-1"))
                .thenReturn(Map.of("driver.pid", "driver-1", "ch#+type", "READ"));

        this.mockMvc.perform(get("/api/v1/wires/assets/asset-1/configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data['driver.pid']").value("driver-1"))
                .andExpect(jsonPath("$.data['ch#+type']").value("READ"));
    }
}
