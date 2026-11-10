/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.security;

import com.kura.wires.rest.controller.SpaForwardController;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 静态托管的放行边界（RFC 005）：前端构建产物匿名可读，{@code /api/**} 仍然要求
 * {@code wires.admin} 凭据——放行静态资源不能顺带放开 REST 层。
 *
 * <p>
 * 静态探针来自 {@code src/test/resources/static/}，与 {@code frontend/dist} 同构
 * （{@code index.html} + {@code assets/*.js}），因此 200 是真实可读证据。
 * </p>
 */
@WebMvcTest(SpaForwardController.class)
@Import(SecurityConfig.class)
class StaticResourceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowAnonymousFrontendAssets() throws Exception {
        givenAnonymousClient();

        final ResultActions script = whenRequesting("/assets/probe.js");
        thenServedAsJavascript(script);
        final ResultActions index = whenRequesting("/index.html");
        thenServedAsHtml(index);
    }

    @Test
    void reportMissingAssetAsNotFoundEnvelope() throws Exception {
        givenAnonymousClient();

        final ResultActions result = whenRequesting("/assets/missing-hash.js");
        thenNotFoundEnvelope(result);
    }

    @Test
    void rejectAnonymousApiRequests() throws Exception {
        givenAnonymousClient();

        final ResultActions result = whenRequesting("/api/v1/wires/metadata");
        thenUnauthorizedEnvelope(result);
    }

    private void givenAnonymousClient() {
        // 不带 @WithMockUser：若安全链误拦静态资源，下面的断言会是 401 而不是 200
    }

    private ResultActions whenRequesting(final String path) throws Exception {
        return this.mockMvc.perform(get(path));
    }

    private void thenServedAsJavascript(final ResultActions result) throws Exception {
        result.andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("kura-edge static probe")));
    }

    private void thenServedAsHtml(final ResultActions result) throws Exception {
        result.andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("id=\"app\"")));
    }

    private void thenNotFoundEnvelope(final ResultActions result) throws Exception {
        // Jackson 全局 non_null（application.yml:21）：data 为 null 时字段缺席，故断言 doesNotExist
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404001))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void thenUnauthorizedEnvelope(final ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401001))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.data").doesNotExist());
    }
}
