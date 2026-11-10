/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import com.kura.wires.rest.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SpaForwardController} 的 Web 层测试：history 模式下的前端路由（含根路径）都交回
 * {@code index.html}，且匿名可访问。
 *
 * <p>
 * 与 {@code SecurityConfig} 一起装配，因此这条测试同时锁住「放行表 ⊇ 转发表」：
 * 新增路由只改 {@link SpaRoutes} 而不改安全放行表时，这里会从 200 变成 401 而红。
 * </p>
 */
@WebMvcTest(SpaForwardController.class)
@Import(SecurityConfig.class)
class SpaForwardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void forwardEverySpaRouteToIndexHtmlForAnonymousClient() throws Exception {
        givenAnonymousClient();

        for (final String route : SpaRoutes.ALL) {
            final ResultActions result = whenOpening(route);
            thenForwardedToIndexHtml(result);
        }
    }

    private void givenAnonymousClient() {
        // 不带 @WithMockUser：这些路由必须匿名可访问
    }

    private ResultActions whenOpening(final String route) throws Exception {
        return this.mockMvc.perform(get(route));
    }

    private void thenForwardedToIndexHtml(final ResultActions result) throws Exception {
        result.andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }
}
