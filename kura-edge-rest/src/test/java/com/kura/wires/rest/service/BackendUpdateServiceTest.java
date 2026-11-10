/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.core.engine.WireGraphEngine;
import com.kura.wires.core.ha.GraphLeadership;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.rest.dto.SystemVersionDto;
import com.kura.wires.rest.dto.UpdateAcceptedDto;
import com.kura.wires.rest.dto.UpdateRequestDto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

/**
 * {@link BackendUpdateService} 单元测试（Gherkin 风格）：受理状态机的正反路径——
 * 成功落盘并通知外壳、摘要不符即丢弃、下载失败可重试、并发第二次被拒、非桌面运行被拒，
 * 以及 {@code /version} 读 {@code current.json} 的降级路径。
 */
class BackendUpdateServiceTest {

    private static final String URL = "https://server.example/kura-edge-1.0.4.jar";

    /** 正常路径的退出延迟：短到测试不用等，但足以让"回执先返回"的顺序成立。 */
    private static final Duration FAST_EXIT = Duration.ofMillis(50);

    @TempDir
    Path root;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StubFetcher fetcher = new StubFetcher();

    /** 发布服务器清单内容（请求不带 url 时才会被读取）。 */
    private String manifestBody = "{}";

    /** 清单地址；空串表示没有配置更新源。 */
    private String feedUrl = "";

    private final AtomicInteger exits = new AtomicInteger();

    private final CountDownLatch exitSignal = new CountDownLatch(1);

    private final GraphLeadership leadership = mock(GraphLeadership.class);

    private final WireGraphEngine engine = mock(WireGraphEngine.class);

    private BackendUpdateService service;

    /** 默认单机语义（isBlueGreen=false、isLeader=true），各用例按需改写。 */
    @BeforeEach
    void stubLeadershipDefaults() {
        when(this.leadership.isBlueGreen()).thenReturn(false);
        when(this.leadership.isLeader()).thenReturn(true);
    }

    @Test
    public void successfulRequestStagesJarAndWritesPendingRequest() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004new-jar".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);

        final UpdateAcceptedDto accepted = whenRequestUpdate(version("1.0.4", payload));

        thenAcceptedMatches(accepted, "1.0.4", payload);
        final JsonNode pending = thenPendingJsonExists();
        assertThat(pending.get("protocol").asInt()).isEqualTo(BackendUpdateService.PROTOCOL);
        assertThat(pending.get("version").asText()).isEqualTo("1.0.4");
        assertThat(pending.get("jar").asText()).isEqualTo("kura-edge-1.0.4.jar");
        assertThat(pending.get("sha256").asText()).isEqualTo(sha256Hex(payload));
        assertThat(pending.get("size").asLong()).isEqualTo(payload.length);
        assertThat(pending.hasNonNull("requestedAt")).isTrue();
        thenStagedJarContains("kura-edge-1.0.4.jar", payload);
        thenStagingHasExactly("kura-edge-1.0.4.jar", "pending.json");
        thenExitHookIsCalled();
    }

    @Test
    public void requestIsRejectedWhenDigestDoesNotMatchDownloadedContent() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004new-jar".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        this.fetcher.digestOverride = "a".repeat(64);

        assertThatThrownBy(() -> whenRequestUpdate(version("1.0.4", payload)))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));

        thenNoPendingFile();
        thenStagingHasExactly();
    }

    @Test
    public void requestIsRejectedWhenDownloadFailsButCanBeRetried() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004new-jar".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        this.fetcher.failure = new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED, "模拟断网");
        final UpdateRequestDto request = version("1.0.4", payload);

        assertThatThrownBy(() -> whenRequestUpdate(request))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_DOWNLOAD_FAILED));
        thenNoPendingFile();

        this.fetcher.failure = null;
        final UpdateAcceptedDto accepted = whenRequestUpdate(request);

        thenAcceptedMatches(accepted, "1.0.4", payload);
        assertThat(this.fetcher.calls.get()).isEqualTo(2);
    }

    @Test
    public void secondRequestWhileFirstIsRunningIsRejected() throws Exception {
        // 故意把退出延迟设得很长：成功受理后进程"还没退出"，第二次请求必须被拒
        givenDesktopRuntime(Duration.ofHours(1));
        final byte[] payload = "PK\u0003\u0004new-jar".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        whenRequestUpdate(version("1.0.4", payload));

        assertThatThrownBy(() -> whenRequestUpdate(version("1.0.5", payload)))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_IN_PROGRESS));

        thenStagedJarContains("kura-edge-1.0.4.jar", payload);
    }

    @Test
    public void requestIsRejectedOutsideDesktopRuntime() throws Exception {
        givenNonDesktopRuntime();
        final byte[] payload = "PK\u0003\u0004new-jar".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);

        assertThatThrownBy(() -> whenRequestUpdate(version("1.0.4", payload)))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_UNSUPPORTED));

        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void leftoverPartialDownloadIsRemovedBeforeInstalling() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004new-jar".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        final Path staging = this.root.resolve("staging");
        Files.createDirectories(staging);
        Files.write(staging.resolve("kura-edge-1.0.2.jar.part"), new byte[] {1, 2, 3});

        whenRequestUpdate(version("1.0.4", payload));

        thenStagingHasExactly("kura-edge-1.0.4.jar", "pending.json");
    }

    @Test
    public void versionIsReadFromShellStateInDesktopRuntime() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        writeCurrentJson("""
                {"protocol":1,"version":"1.0.7","jar":"C:\\\\kura-edge\\\\app\\\\kura-edge-1.0.7.jar",
                 "sha256":"%s","appliedAt":"2026-11-10T03:00:00Z"}
                """.formatted("b".repeat(64)));

        final SystemVersionDto version = whenReadVersion();

        assertThat(version.getVersion()).isEqualTo("1.0.7");
        assertThat(version.getSource()).isEqualTo("desktop");
        assertThat(version.getJar()).endsWith("kura-edge-1.0.7.jar");
        assertThat(version.getSha256()).isEqualTo("b".repeat(64));
    }

    @Test
    public void versionFallsBackToManifestWhenShellStateIsMissingOrCorrupt() throws Exception {
        givenDesktopRuntime(FAST_EXIT);

        final SystemVersionDto missing = whenReadVersion();
        assertThat(missing.getSource()).isEqualTo("maven");
        assertThat(missing.getVersion()).isNotBlank();

        writeCurrentJson("{ not json");
        final SystemVersionDto corrupt = whenReadVersion();
        assertThat(corrupt.getSource()).isEqualTo("maven");
    }

    @Test
    public void versionFallsBackToManifestOutsideDesktopRuntime() {
        givenNonDesktopRuntime();

        assertThat(whenReadVersion().getSource()).isEqualTo("maven");
        assertThat(this.service.supportsHotUpdate()).isFalse();
    }

    @Test
    public void requestWithoutDigestTrustsTheDownloadedArtifact() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004no-digest".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);

        final UpdateAcceptedDto accepted = whenRequestUpdate(new UpdateRequestDto(URL, "", "1.0.4"));

        // 没带摘要就以实际下载到的为准：界面上的"只填网址"走的就是这条路径
        assertThat(accepted.getSha256()).isEqualTo(sha256Hex(payload));
        assertThat(thenPendingJsonExists().get("sha256").asText()).isEqualTo(sha256Hex(payload));
    }

    @Test
    public void requestWithoutVersionDerivesItFromTheFileName() throws Exception {
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004no-version".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        final String url = "https://server/kura-edge-1.0.5.jar";

        final UpdateAcceptedDto accepted = whenRequestUpdate(new UpdateRequestDto(url, sha256Hex(payload), ""));

        assertThat(accepted.getVersion()).isEqualTo("1.0.5");
        assertThat(accepted.getJar()).isEqualTo("kura-edge-1.0.5.jar");
        assertThat(thenPendingJsonExists().get("version").asText()).isEqualTo("1.0.5");
    }

    @Test
    public void requestWithUnderivableVersionIsRejectedBeforeDownloading() {
        givenDesktopRuntime(FAST_EXIT);
        givenFetcherPayload("PK\u0003\u0004x".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> whenRequestUpdate(new UpdateRequestDto("https://server/", "", "")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));
        // 版本推导必须排在 fetch 之前：推导不出来就不该白下一个 60 MB 的包
        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void requestWithoutUrlInstallsTheLatestVersionFromTheReleaseServer() throws Exception {
        this.feedUrl = "http://release.example:8081/feed.json";
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004from-server".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        givenReleaseServerManifest("1.0.4", "kura-edge-1.0.4.jar", sha256Hex(payload));

        final UpdateAcceptedDto accepted = whenRequestUpdate(new UpdateRequestDto("", "", ""));

        // 地址与摘要都来自发布服务器清单：前端只点一个按钮，不再手填 jar 链接
        assertThat(accepted.getVersion()).isEqualTo("1.0.4");
        assertThat(accepted.getSha256()).isEqualTo(sha256Hex(payload));
        assertThat(this.fetcher.lastUrl).isEqualTo("http://release.example:8081/kura-edge-1.0.4.jar");
        assertThat(thenPendingJsonExists().get("version").asText()).isEqualTo("1.0.4");
    }

    @Test
    public void requestWithoutUrlFailsWhenNoUpdateSourceConfigured() {
        givenDesktopRuntime(FAST_EXIT);
        this.feedUrl = "";
        givenFetcherPayload("PK\u0003\u0004x".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> whenRequestUpdate(new UpdateRequestDto("", "", "")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_UNSUPPORTED));
        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void requestWithoutUrlRejectsVersionThatIsNotTheServerLatest() {
        this.feedUrl = "http://release.example:8081/feed.json";
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004x".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);
        givenReleaseServerManifest("1.0.4", "kura-edge-1.0.4.jar", sha256Hex(payload));

        assertThatThrownBy(() -> whenRequestUpdate(new UpdateRequestDto("", "", "1.0.9")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID))
                .hasMessageContaining("1.0.4");
        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void requestWithoutUrlRejectsManifestWithoutDigest() {
        this.feedUrl = "http://release.example:8081/feed.json";
        givenDesktopRuntime(FAST_EXIT);
        givenReleaseServerManifest("1.0.4", "kura-edge-1.0.4.jar", "");

        assertThatThrownBy(() -> whenRequestUpdate(new UpdateRequestDto("", "", "")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));
        assertThat(this.fetcher.calls.get()).isZero();
    }

    private void givenReleaseServerManifest(final String version, final String file, final String sha256) {
        this.manifestBody = """
                {"protocol":1,"latest":{"version":"%s","url":"%s","sha256":"%s","size":0}}
                """.formatted(version, file, sha256);
    }

    @Test
    public void blueGreenRequestKeepsTheOldInstanceServingUntilRetire() throws Exception {
        givenBlueGreenDesktopRuntime();
        final byte[] payload = "PK\u0003\u0004bg".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);

        whenRequestUpdate(version("1.0.4", payload));

        thenPendingJsonExists();
        // 蓝绿模式下旧实例必须继续干活：它要先把页面服务好，等外壳把新实例导航过去再移交
        assertThat(this.exitSignal.await(150, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(this.service.isBlueGreen()).isTrue();
    }

    @Test
    public void retirePausesGraphThenReleasesLeaseThenExits() throws Exception {
        givenBlueGreenDesktopRuntime();

        this.service.retire();

        // 顺序是硬要求：先停 tick 再放锁，否则新实例抢到锁时会与旧实例重复采集
        final InOrder order = inOrder(this.engine, this.leadership);
        order.verify(this.engine).pauseGraph();
        order.verify(this.leadership).release();
        thenExitHookIsCalled();
    }

    @Test
    public void retireIsRejectedInSingleInstanceMode() {
        givenDesktopRuntime(FAST_EXIT);

        assertThatThrownBy(this.service::retire)
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_UNSUPPORTED));
        verify(this.engine, never()).pauseGraph();
    }

    @Test
    public void updateOnStandbyInstanceIsRejected() {
        when(this.leadership.isBlueGreen()).thenReturn(true);
        when(this.leadership.isLeader()).thenReturn(false);
        givenDesktopRuntime(FAST_EXIT);
        final byte[] payload = "PK\u0003\u0004x".getBytes(StandardCharsets.UTF_8);
        givenFetcherPayload(payload);

        assertThatThrownBy(() -> whenRequestUpdate(version("1.0.4", payload)))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_UNSUPPORTED));
        thenNoPendingFile();
    }

    private void givenDesktopRuntime(final Duration exitDelay) {
        this.service = new BackendUpdateService(this.fetcher, this::recordExit, this.objectMapper,
                givenReleaseServer(this.feedUrl), this.leadership, this.engine,
                this.root.resolve("staging").toString(), this.root.resolve("app").toString(), exitDelay);
    }

    /**
     * 发布服务器客户端替身：给定清单地址与清单内容（空串地址 = 未配置更新源）。
     *
     * @param feedUrl 清单地址
     * @return 客户端
     */
    private ReleaseServerClient givenReleaseServer(final String configuredFeedUrl) {
        return new ReleaseServerClient(url -> this.manifestBody, this.objectMapper, "", configuredFeedUrl);
    }

    /** 蓝绿运行时的桌面环境：本进程既是 leader 又具备咨询锁能力。 */
    private void givenBlueGreenDesktopRuntime() {
        when(this.leadership.isBlueGreen()).thenReturn(true);
        when(this.leadership.isLeader()).thenReturn(true);
        givenDesktopRuntime(FAST_EXIT);
    }

    private void givenNonDesktopRuntime() {
        this.service = new BackendUpdateService(this.fetcher, this::recordExit, this.objectMapper,
                givenReleaseServer(""), this.leadership, this.engine, "", "", FAST_EXIT);
    }

    private void givenFetcherPayload(final byte[] payload) {
        this.fetcher.payload = payload.clone();
    }

    private void recordExit() {
        this.exits.incrementAndGet();
        this.exitSignal.countDown();
    }

    private UpdateRequestDto version(final String version, final byte[] payload) {
        return new UpdateRequestDto(URL, sha256Hex(payload), version);
    }

    private UpdateAcceptedDto whenRequestUpdate(final UpdateRequestDto request) {
        return this.service.requestUpdate(request);
    }

    private SystemVersionDto whenReadVersion() {
        return this.service.currentVersion();
    }

    private void thenAcceptedMatches(final UpdateAcceptedDto accepted, final String version, final byte[] payload) {
        assertThat(accepted.getVersion()).isEqualTo(version);
        assertThat(accepted.getJar()).isEqualTo("kura-edge-" + version + ".jar");
        assertThat(accepted.getSha256()).isEqualTo(sha256Hex(payload));
    }

    private JsonNode thenPendingJsonExists() throws IOException {
        final Path pending = this.root.resolve("staging").resolve("pending.json");
        assertThat(pending).exists();
        return this.objectMapper.readTree(Files.readAllBytes(pending));
    }

    private void thenNoPendingFile() {
        assertThat(this.root.resolve("staging").resolve("pending.json")).doesNotExist();
    }

    private void thenStagedJarContains(final String fileName, final byte[] payload) throws IOException {
        final Path jar = this.root.resolve("staging").resolve(fileName);
        assertThat(jar).exists();
        assertThat(Files.readAllBytes(jar)).isEqualTo(payload);
    }

    private void thenStagingHasExactly(final String... names) throws IOException {
        final Path staging = this.root.resolve("staging");
        if (!Files.isDirectory(staging)) {
            assertThat(names).isEmpty();
            return;
        }
        try (Stream<Path> entries = Files.list(staging)) {
            assertThat(entries.map(path -> path.getFileName().toString()).sorted())
                    .containsExactlyElementsOf(Arrays.stream(names).sorted().toList());
        }
    }

    private void thenExitHookIsCalled() throws InterruptedException {
        assertThat(this.exitSignal.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(this.exits.get()).isEqualTo(1);
    }

    private void writeCurrentJson(final String json) throws IOException {
        final Path app = this.root.resolve("app");
        Files.createDirectories(app);
        Files.write(app.resolve("current.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(final byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 可控的下载器替身：按需给字节、改摘要，或直接抛异常。 */
    private static final class StubFetcher implements ArtifactFetcher {

        private byte[] payload = new byte[0];

        private String digestOverride;

        private RuntimeException failure;

        /** 最近一次下载用的地址（校验"地址从清单来"用）。 */
        private String lastUrl;

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public FetchedArtifact fetch(final String url, final Path partFile, final long maxBytes) {
            this.calls.incrementAndGet();
            this.lastUrl = url;
            if (this.failure != null) {
                throw this.failure;
            }
            try {
                Files.createDirectories(partFile.getParent());
                Files.write(partFile, this.payload);
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
            final String digest = this.digestOverride == null ? sha256Hex(this.payload) : this.digestOverride;
            return new FetchedArtifact(this.payload.length, digest);
        }
    }
}
