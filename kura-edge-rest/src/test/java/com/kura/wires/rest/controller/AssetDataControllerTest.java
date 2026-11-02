/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.type.DataType;
import com.kura.wires.rest.dto.AssetChannelDto;
import com.kura.wires.rest.dto.ChannelRecordDto;
import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.AssetDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link AssetDataController} 的 Web 层测试：通道列表、读值形态（纯映射）与写值返回状态。
 */
@WebMvcTest(AssetDataController.class)
@Import(SecurityConfig.class)
class AssetDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssetDataService service;

    @Test
    @WithMockUser(roles = "wires.admin")
    void listAssetChannels() throws Exception {
        when(this.service.getChannels("asset-1")).thenReturn(List.of(new AssetChannelDto("temp", ChannelType.READ,
                DataType.INTEGER, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 1.0d, 0.0d, "C", true)));

        this.mockMvc.perform(get("/api/v1/assets/asset-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("temp"))
                .andExpect(jsonPath("$.data[0].valueType").value("INTEGER"))
                .andExpect(jsonPath("$.data[0].unit").value("C"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void readAllChannelsAsPlainMap() throws Exception {
        when(this.service.readAll("asset-1")).thenReturn(Map.of("temp", 21));

        this.mockMvc.perform(get("/api/v1/assets/asset-1/_read")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temp").value(21));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void readSelectedChannelsReturnsRecordsWithStatus() throws Exception {
        when(this.service.read(eq("asset-1"), any(Set.class))).thenReturn(
                List.of(new ChannelRecordDto("temp", DataType.INTEGER, 21, "SUCCESS", null)));

        this.mockMvc.perform(post("/api/v1/assets/asset-1/_read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channels\":[\"temp\"]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("temp"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void rejectReadWithoutChannelNames() throws Exception {
        this.mockMvc.perform(post("/api/v1/assets/asset-1/_read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channels\":[]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.VALIDATION_FAILED.getCode())));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void writeChannelsReturnsPostWriteStatus() throws Exception {
        when(this.service.write(eq("asset-1"), any())).thenReturn(
                List.of(new ChannelRecordDto("setpoint", DataType.INTEGER, 5, "FAILURE", "write failed")));

        this.mockMvc.perform(post("/api/v1/assets/asset-1/_write").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channels\":[{\"name\":\"setpoint\",\"type\":\"INTEGER\",\"value\":5}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("FAILURE"))
                .andExpect(jsonPath("$.data[0].exceptionMessage").value("write failed"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void rejectWriteWithoutType() throws Exception {
        this.mockMvc.perform(post("/api/v1/assets/asset-1/_write").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channels\":[{\"name\":\"setpoint\",\"value\":5}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    void returnNotFoundForUnknownAsset() throws Exception {
        when(this.service.readAll("missing")).thenThrow(new WiresException(ErrorCode.NOT_FOUND,
                "Asset not found: missing"));

        this.mockMvc.perform(get("/api/v1/assets/missing/_read")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(Integer.parseInt(ErrorCode.NOT_FOUND.getCode())))
                .andExpect(jsonPath("$.message").value("Asset not found: missing"));
    }
}
