/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.rest.dto.FeedStatusDto;
import com.kura.wires.rest.dto.SystemVersionDto;
import com.kura.wires.rest.dto.UpdateAcceptedDto;
import com.kura.wires.rest.security.SecurityConfig;
import com.kura.wires.rest.service.BackendUpdateService;
import com.kura.wires.rest.service.UpdateFeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link SystemUpdateController} 的 Web 层测试（Gherkin 风格）：
 * 权限（401 / 403）、版本查询（含蓝绿角色）、更新受理（202）、蓝绿移交（202 / 409）、
 * Bean Validation（400）与业务错误码映射（409 / 500）。
 */
@WebMvcTest(SystemUpdateController.class)
@Import(SecurityConfig.class)
class SystemUpdateControllerTest {

    private static final String VERSION_URL = "/api/v1/system/version";

    private static final String UPDATE_URL = "/api/v1/system/update";

    private static final String RETIRE_URL = "/api/v1/system/retire";

    private static final String FEED_URL = "/api/v1/system/feed";

    private static final String FEED_CHECK_URL = "/api/v1/system/feed/check";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BackendUpdateService updateService;

    @MockBean
    private UpdateFeedService feedService;

    @Test
    public void rejectUnauthenticatedVersionRequest() throws Exception {
        this.mockMvc.perform(get(VERSION_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    public void rejectUnauthenticatedFeedRequest() throws Exception {
        this.mockMvc.perform(get(FEED_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "viewer")
    public void rejectFeedWithoutWiresAdminRole() throws Exception {
        this.mockMvc.perform(get(FEED_URL)).andExpect(status().isForbidden());
        this.mockMvc.perform(post(FEED_CHECK_URL)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void returnFeedStatusForAuthorizedUser() throws Exception {
        when(this.feedService.status()).thenReturn(feedStatus());

        this.mockMvc.perform(get(FEED_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.url").value("http://server/feed.json"))
                .andExpect(jsonPath("$.data.intervalMs").value(300000))
                .andExpect(jsonPath("$.data.lastStagedVersion").value("1.0.4"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void checkFeedNowForAuthorizedUser() throws Exception {
        when(this.feedService.checkNow()).thenReturn(feedStatus());

        this.mockMvc.perform(post(FEED_CHECK_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestSeenVersion").value("1.0.4"));
        verify(this.feedService).checkNow();
    }

    private FeedStatusDto feedStatus() {
        final FeedStatusDto status = new FeedStatusDto();
        status.setEnabled(true);
        status.setUrl("http://server/feed.json");
        status.setIntervalMs(300_000L);
        status.setLastCheckOk(true);
        status.setLatestSeenVersion("1.0.4");
        status.setLastStagedVersion("1.0.4");
        return status;
    }

    @Test
    public void rejectUnauthenticatedRetireRequest() throws Exception {
        this.mockMvc.perform(post(RETIRE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "viewer")
    public void rejectRetireWithoutWiresAdminRole() throws Exception {
        this.mockMvc.perform(post(RETIRE_URL)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void acceptRetireWith202() throws Exception {
        this.mockMvc.perform(post(RETIRE_URL))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0));
        verify(this.updateService).retire();
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void rejectRetireInSingleInstanceMode() throws Exception {
        doThrow(new WiresException(ErrorCode.UPDATE_UNSUPPORTED, "单机模式不支持蓝绿移交"))
                .when(this.updateService).retire();

        this.mockMvc.perform(post(RETIRE_URL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409004));
    }

    @Test
    @WithMockUser(roles = "viewer")
    public void rejectUpdateWithoutWiresAdminRole() throws Exception {
        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void returnCurrentVersionForAuthorizedUser() throws Exception {
        givenVersion("1.0.4", "desktop");

        this.mockMvc.perform(get(VERSION_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value("1.0.4"))
                .andExpect(jsonPath("$.data.source").value("desktop"))
                .andExpect(jsonPath("$.data.role").value("leader"))
                .andExpect(jsonPath("$.data.blueGreen").value(true));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void acceptValidUpdateRequestWith202() throws Exception {
        givenAccepted("1.0.4");

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value("1.0.4"))
                .andExpect(jsonPath("$.data.jar").value("kura-edge-1.0.4.jar"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void acceptUpdateRequestWithOnlyUrl() throws Exception {
        givenAccepted("1.0.4");
        final String body = """
                {"url":"https://server/kura-edge-1.0.4.jar","sha256":"","version":""}
                """;

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void acceptUpdateRequestWithOnlyVersionForTheReleaseServer() throws Exception {
        givenAccepted("1.0.4");
        final String body = """
                {"version":"1.0.4"}
                """;

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.version").value("1.0.4"));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void rejectUpdateRequestWithNonHttpUrl() throws Exception {
        final String body = """
                {"url":"ftp://server/kura-edge-1.0.4.jar"}
                """;

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void rejectUpdateRequestWithMalformedDigest() throws Exception {
        final String body = """
                {"url":"https://server/kura-edge-1.0.4.jar","sha256":"not-a-digest","version":"1.0.4"}
                """;

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void rejectUpdateRequestWithPathTraversalVersion() throws Exception {
        final String body = """
                {"url":"https://server/x.jar","sha256":"%s","version":"../evil"}
                """.formatted("a".repeat(64));

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void reportConflictWhenNotRunningAsDesktop() throws Exception {
        givenUpdateFails(new WiresException(ErrorCode.UPDATE_UNSUPPORTED, "当前不是桌面运行时"));

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409004));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void reportConflictWhenAnotherUpdateIsRunning() throws Exception {
        givenUpdateFails(new WiresException(ErrorCode.UPDATE_IN_PROGRESS, "已有更新在进行"));

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409005));
    }

    @Test
    @WithMockUser(roles = "wires.admin")
    public void reportServerErrorWhenDownloadFails() throws Exception {
        givenUpdateFails(new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED, "下载失败"));

        this.mockMvc.perform(post(UPDATE_URL).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500004));
    }

    private void givenVersion(final String version, final String source) {
        when(this.updateService.currentVersion())
                .thenReturn(new SystemVersionDto(version, "C:/kura-edge/app/kura-edge-" + version + ".jar",
                        "a".repeat(64), source, "leader", true));
    }

    private void givenAccepted(final String version) {
        when(this.updateService.requestUpdate(any())).thenReturn(
                new UpdateAcceptedDto(version, "kura-edge-" + version + ".jar", "a".repeat(64)));
    }

    private void givenUpdateFails(final WiresException exception) {
        when(this.updateService.requestUpdate(any())).thenThrow(exception);
    }

    private String validBody() {
        return """
                {"url":"https://server/kura-edge-1.0.4.jar","sha256":"%s","version":"1.0.4"}
                """.formatted("a".repeat(64));
    }
}
