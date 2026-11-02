/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.DriverDescriptorDto;
import com.kura.wires.rest.dto.PidAndFactoryPidDto;
import com.kura.wires.rest.dto.WireComponentDefinitionDto;
import com.kura.wires.rest.dto.WireGraphMetadataDto;
import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.DriversAssetsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WireMetadataController} 的 Web 层测试：组件定义、驱动工厂 PID、驱动通道描述符与资产基座描述符。
 */
@WebMvcTest(WireMetadataController.class)
@Import(SecurityConfig.class)
class WireMetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DriversAssetsService service;

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetMetadata() throws Exception {
        final WireComponentDefinitionDto definition = new WireComponentDefinitionDto("org.eclipse.kura.wire.Timer", 0, 0,
                0, 1, 1, 1, null, null, List.of());
        final ComponentConfigurationDto driverOcd = new ComponentConfigurationDto("org.eclipse.kura.driver.simulated",
                null, null);
        final DriverDescriptorDto descriptor = new DriverDescriptorDto("driver-1", "org.eclipse.kura.driver.simulated",
                List.of(new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "r")));
        when(this.service.getMetadata()).thenReturn(new WireGraphMetadataDto(List.of(definition), List.of(driverOcd),
                List.of(descriptor), List.of(new Ad("+enabled", "enabled", Scalar.BOOLEAN, true, true))));

        this.mockMvc.perform(get("/api/v1/wires/metadata")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.wireComponentDefinitions[0].factoryPid")
                        .value("org.eclipse.kura.wire.Timer"))
                .andExpect(jsonPath("$.data.driverOCDs[0].pid").value("org.eclipse.kura.driver.simulated"))
                .andExpect(jsonPath("$.data.driverChannelDescriptors[0].channelDescriptor[0].id")
                        .value("simulated.register"))
                .andExpect(jsonPath("$.data.assetChannelDescriptor[0].id").value("+enabled"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void omitEmptyListsFromMetadata() throws Exception {
        when(this.service.getMetadata()).thenReturn(new WireGraphMetadataDto(List.of(), null, null, null));

        this.mockMvc.perform(get("/api/v1/wires/metadata")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wireComponentDefinitions").isArray())
                .andExpect(jsonPath("$.data.driverOCDs").doesNotExist())
                .andExpect(jsonPath("$.data.driverChannelDescriptors").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetDriverFactoryPids() throws Exception {
        when(this.service.getDriverFactoryPids()).thenReturn(List.of("org.eclipse.kura.driver.simulated"));

        this.mockMvc.perform(get("/api/v1/wires/metadata/drivers/factory-pids")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pids[0]").value("org.eclipse.kura.driver.simulated"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetDriverChannelDescriptors() throws Exception {
        when(this.service.listDrivers()).thenReturn(List.of(new PidAndFactoryPidDto("driver-1", "test.driver")));
        when(this.service.getDriverChannelDescriptor("driver-1"))
                .thenReturn(List.of(new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "r")));

        this.mockMvc.perform(get("/api/v1/wires/metadata/drivers/channel-descriptors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pid").value("driver-1"))
                .andExpect(jsonPath("$.data[0].channelDescriptor[0].id").value("simulated.register"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void successfulGetAssetChannelDescriptor() throws Exception {
        when(this.service.getBaseChannelDescriptor())
                .thenReturn(List.of(new Ad("+enabled", "enabled", Scalar.BOOLEAN, true, true)));

        this.mockMvc.perform(get("/api/v1/wires/metadata/assets/channel-descriptor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("+enabled"));
    }

    @Test
    void rejectMetadataWithoutRole() throws Exception {
        this.mockMvc.perform(get("/api/v1/wires/metadata")).andExpect(status().isUnauthorized());
    }
}
