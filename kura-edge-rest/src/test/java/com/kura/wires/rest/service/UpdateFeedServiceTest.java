/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.core.engine.WireGraphEngine;
import com.kura.wires.core.ha.GraphLeadership;
import com.kura.wires.rest.dto.FeedStatusDto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link UpdateFeedService} 的判定测试（Gherkin 风格）：拉清单 → 比版本 → 落包的三段逻辑，
 * 以及四条"宁可不动"的护栏（不降级、已有 pending 就跳过、失败冷却、非桌面不启用）。
 * <p>
 * 这里刻意用**真实的** {@link BackendUpdateService}（只桩掉下载器与清单读取器）：轮询路径最怕的就是
 * 自己另写一套下载/校验逻辑，那样"自动更新能装、手动更新装不上"之类的差异迟早会漏到现场。
 */
class UpdateFeedServiceTest {

    private static final String FEED_URL = "http://192.168.31.40:8000/releases/feed.json";

    private static final Duration FAST_EXIT = Duration.ofMillis(50);

    @TempDir
    Path root;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final GraphLeadership leadership = mock(GraphLeadership.class);

    private final WireGraphEngine engine = mock(WireGraphEngine.class);

    private final StubFetcher fetcher = new StubFetcher();

    private final StubLoader loader = new StubLoader();

    private BackendUpdateService updateService;

    private UpdateFeedService feedService;

    @BeforeEach
    void givenBlueGreenDesktopRuntime() {
        // 蓝绿模式：落包后不退出进程，测试不会真的把 JVM 关掉
        when(this.leadership.isBlueGreen()).thenReturn(true);
        when(this.leadership.isLeader()).thenReturn(true);
        this.updateService = new BackendUpdateService(this.fetcher, () -> {
            // 蓝绿路径不该调用退出钩子；真调到了就让断言失败得更明显
            throw new IllegalStateException("蓝绿模式下不应退出进程");
        }, this.objectMapper, givenClient(FEED_URL), this.leadership, this.engine,
                this.root.resolve("staging").toString(), this.root.resolve("app").toString(), FAST_EXIT);
        this.feedService = new UpdateFeedService(this.updateService, givenClient(FEED_URL), this.objectMapper,
                300_000L, 1_800_000L);
    }

    @Test
    public void stagesNewerVersionFromTheManifest() throws Exception {
        givenLocalVersion("1.0.3");
        final byte[] payload = "PK\u0003\u0004feed".getBytes(StandardCharsets.UTF_8);
        givenPayload(payload);
        givenManifest(manifest("1.0.4", "kura-edge-1.0.4.jar", sha256Hex(payload)));

        whenPoll();

        assertThat(thenPendingVersion()).isEqualTo("1.0.4");
        assertThat(this.fetcher.calls.get()).isEqualTo(1);
        // 相对地址要按清单自身地址解析：整目录上传到服务器就能用
        assertThat(this.fetcher.lastUrl).isEqualTo("http://192.168.31.40:8000/releases/kura-edge-1.0.4.jar");
        final FeedStatusDto status = this.feedService.status();
        assertThat(status.isLastCheckOk()).isTrue();
        assertThat(status.getLastStagedVersion()).isEqualTo("1.0.4");
        assertThat(status.getLatestSeenVersion()).isEqualTo("1.0.4");
    }

    @Test
    public void ignoresManifestThatIsNotNewer() throws Exception {
        givenLocalVersion("1.0.3");
        givenPayload("PK\u0003\u0004x".getBytes(StandardCharsets.UTF_8));
        givenManifest(manifest("1.0.3", "kura-edge-1.0.3.jar", "a".repeat(64)));

        whenPoll();

        assertThat(this.fetcher.calls.get()).isZero();
        assertThat(this.root.resolve("staging").resolve("pending.json")).doesNotExist();
        assertThat(this.feedService.status().getLatestSeenVersion()).isEqualTo("1.0.3");
    }

    @Test
    public void rejectsManifestWithIllegalVersionBeforeDownloading() throws Exception {
        givenLocalVersion("1.0.3");
        givenPayload("PK\u0003\u0004x".getBytes(StandardCharsets.UTF_8));
        givenManifest(manifest("../../etc/evil", "kura-edge.jar", "a".repeat(64)));

        whenPoll();

        // 版本号会拼进落地文件名：非法版本必须在下载之前就拒掉，否则就是路径穿越
        assertThat(this.fetcher.calls.get()).isZero();
        assertThat(this.feedService.status().getLastError()).contains("版本号非法");
    }

    @Test
    public void skipsWhileAPendingRequestIsStillWaiting() throws Exception {
        givenLocalVersion("1.0.3");
        final byte[] payload = "PK\u0003\u0004feed".getBytes(StandardCharsets.UTF_8);
        givenPayload(payload);
        givenManifest(manifest("1.0.4", "kura-edge-1.0.4.jar", sha256Hex(payload)));
        // 上一轮留下的 pending.json 还在（外壳没处理完）
        Files.createDirectories(this.root.resolve("staging"));
        Files.write(this.root.resolve("staging").resolve("pending.json"), "{\"protocol\":1}".getBytes(
                StandardCharsets.UTF_8));

        whenPoll();

        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void coolsDownAfterTheShellRolledBackThatVersion() throws Exception {
        givenLocalVersion("1.0.3");
        final byte[] payload = "PK\u0003\u0004feed".getBytes(StandardCharsets.UTF_8);
        givenPayload(payload);
        givenManifest(manifest("1.0.4", "kura-edge-1.0.4.jar", sha256Hex(payload)));
        Files.createDirectories(this.root.resolve("app"));
        Files.write(this.root.resolve("app").resolve("apply-result.json"), """
                {"protocol":1,"status":"rolled_back","version":"1.0.4","error":"起不来","at":"%s"}
                """.formatted(java.time.Instant.now()).getBytes(StandardCharsets.UTF_8));

        whenPoll();

        // 冷却期内不再下载：否则一台"装不上"的设备会每 5 分钟拉 60 MB 再把自己折腾一遍
        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void recordsFailureAndStopsRetryingAfterDigestMismatch() throws Exception {
        givenLocalVersion("1.0.3");
        final byte[] payload = "PK\u0003\u0004feed".getBytes(StandardCharsets.UTF_8);
        givenPayload(payload);
        givenManifest(manifest("1.0.4", "kura-edge-1.0.4.jar", "b".repeat(64)));

        whenPoll();

        final FeedStatusDto status = this.feedService.status();
        assertThat(this.fetcher.calls.get()).isEqualTo(1);
        assertThat(status.getFailedVersion()).isEqualTo("1.0.4");
        assertThat(status.getLastError()).contains("sha256");

        whenPoll();
        assertThat(this.fetcher.calls.get()).isEqualTo(1);
    }

    @Test
    public void disabledWhenNoFeedUrlConfigured() {
        final UpdateFeedService disabled = new UpdateFeedService(this.updateService, givenClient(""),
                this.objectMapper, 300_000L, 1_800_000L);

        assertThat(disabled.enabled()).isFalse();
        disabled.pollQuietly();

        assertThat(this.loader.calls.get()).isZero();
        assertThat(this.fetcher.calls.get()).isZero();
    }

    @Test
    public void reportsManifestFetchFailureInsteadOfThrowing() throws Exception {
        givenLocalVersion("1.0.3");
        this.loader.failure = new com.kura.wires.exception.WiresException(
                com.kura.wires.exception.ErrorCode.UPDATE_DOWNLOAD_FAILED, "拉取更新清单失败：HTTP 404");

        whenPoll();

        final FeedStatusDto status = this.feedService.status();
        assertThat(status.isLastCheckOk()).isFalse();
        assertThat(status.getLastError()).contains("HTTP 404");
    }

    /**
     * 客户端：显式给清单地址（等价"nginx 静态发版"），保留本用例对相对地址解析的验证。
     *
     * @param feedUrl 清单地址，空串表示未配置
     * @return 客户端
     */
    private ReleaseServerClient givenClient(final String feedUrl) {
        return new ReleaseServerClient(this.loader, this.objectMapper, "", feedUrl);
    }

    private void whenPoll() {
        this.feedService.pollQuietly();
    }

    private void givenLocalVersion(final String version) throws IOException {
        final Path app = this.root.resolve("app");
        Files.createDirectories(app);
        Files.write(app.resolve("current.json"), """
                {"protocol":1,"version":"%s","jar":"%s","sha256":"%s","appliedAt":"2026-11-10T00:00:00Z"}
                """.formatted(version, app.resolve("kura-edge-" + version + ".jar"), "c".repeat(64))
                .getBytes(StandardCharsets.UTF_8));
    }

    private void givenPayload(final byte[] payload) {
        this.fetcher.payload = payload.clone();
    }

    private String thenPendingVersion() throws IOException {
        final Path pending = this.root.resolve("staging").resolve("pending.json");
        assertThat(pending).exists();
        return this.objectMapper.readTree(Files.readAllBytes(pending)).get("version").asText();
    }

    private void givenManifest(final String json) {
        this.loader.body = json;
    }

    private static String manifest(final String version, final String url, final String sha256) {
        return """
                {"protocol":1,"latest":{"version":"%s","url":"%s","sha256":"%s","size":0,
                 "publishedAt":"2026-11-10T09:30:00Z","notes":"test"}}
                """.formatted(version, url, sha256);
    }

    private static String sha256Hex(final byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 清单读取器替身。 */
    private static final class StubLoader implements ManifestLoader {

        private String body = "{}";

        private RuntimeException failure;

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String load(final String url) {
            this.calls.incrementAndGet();
            if (this.failure != null) {
                throw this.failure;
            }
            return this.body;
        }
    }

    /** 下载器替身：只负责把内容写到 .part 文件。 */
    private static final class StubFetcher implements ArtifactFetcher {

        private byte[] payload = new byte[0];

        private String lastUrl;

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public FetchedArtifact fetch(final String url, final Path partFile, final long maxBytes) {
            this.calls.incrementAndGet();
            this.lastUrl = url;
            try {
                Files.createDirectories(partFile.getParent());
                Files.write(partFile, this.payload);
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new FetchedArtifact(this.payload.length, sha256Hex(this.payload));
        }
    }
}
