/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.ComponentLogStreamer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link WireComponentLogController} 的 Web 层测试：SSE 端点权限（401 / 403）与
 * 授权后进入异步流。
 */
@WebMvcTest(WireComponentLogController.class)
@Import(SecurityConfig.class)
class WireComponentLogControllerTest {

    private static final String STREAM_URL = "/api/v1/wires/graph/components/logger-1/log/stream";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComponentLogStreamer logStreamer;

    @Test
    public void rejectUnauthenticatedRequest() throws Exception {
        this.mockMvc.perform(get(STREAM_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "viewer")
    public void rejectRequestWithoutWiresAdminRole() throws Exception {
        this.mockMvc.perform(get(STREAM_URL)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void successfulAuthorizedRequestStartsEventStream() throws Exception {
        givenSubscription();

        this.mockMvc.perform(get(STREAM_URL)).andExpect(status().isOk()).andExpect(request().asyncStarted());
    }

    private void givenSubscription() {
        when(this.logStreamer.subscribe("logger-1", 0L)).thenReturn(new SseEmitter(60_000L));
    }
}
