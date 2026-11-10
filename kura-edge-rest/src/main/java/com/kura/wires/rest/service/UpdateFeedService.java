/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.exception.WiresException;
import com.kura.wires.update.VersionComparator;
import com.kura.wires.update.FeedManifestDto;
import com.kura.wires.rest.dto.FeedStatusDto;
import com.kura.wires.rest.dto.UpdateRequestDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * 自动更新：定时拉服务器上的清单，发现更新的版本就自己走热更新流程（RFC 008）。
 * <p>
 * 为什么要"拉"而不是"推"：现场的部署形态是**上位机只能单向访问服务器**，任何"服务器连设备"的方案
 * 都过不了防火墙。所以更新源只提供一个静态 {@code feed.json}（发布脚本顺手生成），设备每
 * {@code kura.update.feed-interval-ms} 拉一次，发现比本机新就下载并校验，然后**复用 /update 的同一套
 * 落地逻辑**（写 {@code staging/pending.json}）——之后的蓝绿交接由桌面外壳完成，本类不碰进程与文件指针。
 * </p>
 * <p>
 * 几条自我约束（都是"宁可不动"的判断）：
 * </p>
 * <ul>
 *   <li>只升不降：清单版本必须**比本机新**才动；要回退请用页面上的手动升级。</li>
 *   <li>本机版本未知（{@code dev}/{@code unknown}）不升级——别把开发机刷成发布版。</li>
 *   <li>{@code staging/pending.json} 还在（外壳没处理完）就跳过，避免覆盖正在进行的更新。</li>
 *   <li>同一版本提交过就不再重复提交；失败过的版本有 {@code retry-after-ms} 冷却期，
 *       避免一台装不上的设备每 5 分钟下载 60 MB。</li>
 *   <li>非桌面运行时（没有 {@code staging/}）直接不启动轮询。</li>
 * </ul>
 */
@Service
public class UpdateFeedService {

    private static final Logger log = LoggerFactory.getLogger(UpdateFeedService.class);

    /** 清单协议版本：与 {@code build-desktop.sh} 生成的一致。 */
    static final int PROTOCOL = 1;

    /** 轮询状态文件（后端独占写的 {@code staging/} 内，外壳不读它）。 */
    static final String STATE_FILE = "feed-state.json";

    /** 外壳处理完请求后的结果文件（后端只读，用于识别"这一版装不上"）。 */
    private static final String RESULT_FILE = "apply-result.json";

    /** 启动后第一次轮询的延迟：别和后端起停、图恢复抢时间。 */
    static final long FIRST_POLL_DELAY_MS = 30_000L;

    private final BackendUpdateService updateService;

    private final ReleaseServerClient releaseServer;

    private final ObjectMapper objectMapper;


    private final long feedIntervalMs;

    private final long retryAfterMs;

    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean polling = new AtomicBoolean(false);

    /**
     * Spring 构造器。
     *
     * @param updateService 热更新受理服务（复用它的下载校验与 pending.json 落地）
     * @param releaseServer 发布服务器客户端（清单地址由它推导，RFC 010）
     * @param objectMapper JSON 序列化器
     * @param feedIntervalMs 轮询间隔（毫秒）
     * @param retryAfterMs 失败版本的重试冷却期（毫秒）
     */
    @Autowired
    public UpdateFeedService(final BackendUpdateService updateService, final ReleaseServerClient releaseServer,
            final ObjectMapper objectMapper,
            @Value("${kura.update.feed-interval-ms:300000}") final long feedIntervalMs,
            @Value("${kura.update.retry-after-ms:1800000}") final long retryAfterMs) {
        this.updateService = updateService;
        this.releaseServer = releaseServer;
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.feedIntervalMs = Math.max(10_000L, feedIntervalMs);
        this.retryAfterMs = Math.max(0L, retryAfterMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "kura-edge-update-feed");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 是否启用了自动更新：配了清单地址，且是桌面运行时（后端才有地方写 {@code pending.json}）。
     *
     * @return true 表示会自动轮询
     */
    public boolean enabled() {
        return this.releaseServer.configured() && this.updateService.supportsHotUpdate();
    }

    /**
     * 启动轮询（仅在启用时）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled()) {
            if (this.releaseServer.configured()) {
                log.info("自动更新已配置清单 {}，但当前不是桌面运行时，不启用轮询", this.releaseServer.feedUrl());
            }
            return;
        }
        log.info("自动更新已启用：清单 {}，每 {} s 检查一次", this.releaseServer.feedUrl(), this.feedIntervalMs / 1000);
        this.scheduler.scheduleWithFixedDelay(this::pollQuietly, FIRST_POLL_DELAY_MS, this.feedIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 停止轮询（Spring 销毁回调）。
     */
    @PreDestroy
    public void stop() {
        this.scheduler.shutdownNow();
    }

    /**
     * 当前状态：配置 + 上一次轮询结果（{@code GET /api/v1/system/feed}）。
     *
     * @return 状态
     */
    public FeedStatusDto status() {
        final FeedStatusDto status = new FeedStatusDto();
        status.setEnabled(enabled());
        status.setUrl(this.releaseServer.feedUrl());
        status.setServerUrl(this.releaseServer.releaseServerUrl());
        status.setIntervalMs(this.feedIntervalMs);
        final FeedState state = readState();
        status.setLastCheckAt(nullToEmpty(state.lastCheckAt));
        status.setLastCheckOk(state.lastCheckOk);
        status.setLastError(nullToEmpty(state.lastError));
        status.setLatestSeenVersion(nullToEmpty(state.latestSeenVersion));
        status.setLastStagedVersion(nullToEmpty(state.lastStagedVersion));
        status.setLastStagedAt(nullToEmpty(state.lastStagedAt));
        status.setFailedVersion(nullToEmpty(state.failedVersion));
        status.setFailedAt(nullToEmpty(state.failedAt));
        return status;
    }

    /**
     * 立即检查一次（{@code POST /api/v1/system/feed/check}，供运维手工触发）。
     *
     * @return 检查后的状态
     */
    public FeedStatusDto checkNow() {
        pollQuietly();
        return status();
    }

    /**
     * 一次轮询：拉清单 → 比对版本 → 满足条件就交给 {@link BackendUpdateService} 落包。
     * <p>
     * 包内可见以便单测直接驱动，不必等真实定时器。
     */
    void pollQuietly() {
        if (!enabled() || !this.polling.compareAndSet(false, true)) {
            return;
        }
        try {
            pollOnce();
        } catch (final RuntimeException exception) {
            log.warn("自动更新轮询异常：{}", exception.getMessage());
        } finally {
            this.polling.set(false);
        }
    }

    private void pollOnce() {
        final FeedState state = readState();
        state.lastCheckAt = Instant.now().toString();
        state.lastCheckOk = false;
        try {
            final FeedManifestDto.Latest latest = this.releaseServer.fetch().getLatest();
            state.lastCheckOk = true;
            state.latestSeenVersion = latest.getVersion();
            state.lastError = null;
            decideAndStage(state, latest);
        } catch (final WiresException exception) {
            state.lastError = exception.getMessage();
            log.warn("自动更新：清单检查失败：{}", exception.getMessage());
        } finally {
            writeState(state);
        }
    }

    private void decideAndStage(final FeedState state, final FeedManifestDto.Latest latest) {
        if (!BackendUpdateService.VERSION_PATTERN.matcher(latest.getVersion() == null ? "" : latest.getVersion())
                .matches()) {
            state.lastError = "清单里的版本号非法：" + latest.getVersion();
            log.warn("自动更新：{}", state.lastError);
            return;
        }
        final String current = this.updateService.currentVersion().getVersion();
        if (!VersionComparator.isNewer(latest.getVersion(), current)) {
            log.debug("自动更新：清单 {} 不新于本机 {}，无需动作", latest.getVersion(), current);
            return;
        }
        if (pendingRequestExists()) {
            log.info("自动更新：本机已有待应用的更新请求（pending.json），本次跳过");
            return;
        }
        if (latest.getVersion().equals(state.lastStagedVersion)) {
            log.info("自动更新：{} 已提交过，等外壳处理", latest.getVersion());
            return;
        }
        if (isCoolingDown(state, latest.getVersion())) {
            log.info("自动更新：{} 上次失败，冷却中（{} s 后再试）", latest.getVersion(), this.retryAfterMs / 1000);
            return;
        }
        final String url = this.releaseServer.jarUrl(latest);
        try {
            this.updateService.requestUpdate(new UpdateRequestDto(url, latest.getSha256(), latest.getVersion()));
            state.lastStagedVersion = latest.getVersion();
            state.lastStagedAt = Instant.now().toString();
            state.failedVersion = null;
            state.failedAt = null;
            log.info("自动更新：{} 已提交给外壳（{}）", latest.getVersion(), url);
        } catch (final WiresException exception) {
            state.lastError = exception.getMessage();
            state.failedVersion = latest.getVersion();
            state.failedAt = Instant.now().toString();
            log.warn("自动更新：提交 {} 失败：{}", latest.getVersion(), exception.getMessage());
        }
    }

    /**
     * 失败冷却判定：同一版本失败过，且没超过 {@code retry-after-ms} 就跳过。
     * <p>
     * 失败来源有两处：本类提交时抛错（{@code failedVersion}），以及外壳应用后回滚
     * （{@code app/apply-result.json} 里 {@code status=rolled_back}）——后者才是现场最常见的
     * "能下载、起不来"，必须一起算进冷却，否则每轮都会重新下载 60 MB 再把设备折腾一次。
     */
    private boolean isCoolingDown(final FeedState state, final String version) {
        final Instant now = Instant.now();
        if (version.equals(state.failedVersion) && withinCooldown(state.failedAt, now)) {
            return true;
        }
        return version.equals(rolledBackVersion()) && withinCooldown(lastResultAt(), now);
    }

    private boolean withinCooldown(final String at, final Instant now) {
        if (at == null || at.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(at).plusMillis(this.retryAfterMs).isAfter(now);
        } catch (final RuntimeException exception) {
            return false;
        }
    }

    private String rolledBackVersion() {
        final Path path = this.updateService.homePath(RESULT_FILE);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            final var node = this.objectMapper.readTree(Files.readAllBytes(path));
            final var status = node.get("status");
            final var version = node.get("version");
            if (status == null || version == null) {
                return null;
            }
            return "rolled_back".equals(status.asText()) ? version.asText() : null;
        } catch (final IOException | RuntimeException exception) {
            return null;
        }
    }

    private String lastResultAt() {
        final Path path = this.updateService.homePath(RESULT_FILE);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            final var node = this.objectMapper.readTree(Files.readAllBytes(path));
            final var at = node.get("at");
            return at == null ? null : at.asText();
        } catch (final IOException | RuntimeException exception) {
            return null;
        }
    }

    private boolean pendingRequestExists() {
        final var staging = this.updateService.stagingPath();
        return staging.isPresent() && Files.isRegularFile(staging.get().resolve("pending.json"));
    }

    private FeedState readState() {
        final var staging = this.updateService.stagingPath();
        if (staging.isEmpty()) {
            return new FeedState();
        }
        final Path path = staging.get().resolve(STATE_FILE);
        if (!Files.isRegularFile(path)) {
            return new FeedState();
        }
        try {
            return this.objectMapper.readValue(Files.readAllBytes(path), FeedState.class);
        } catch (final IOException | RuntimeException exception) {
            log.warn("自动更新状态文件无法解析，按全新状态处理：{}", exception.getMessage());
            return new FeedState();
        }
    }

    private void writeState(final FeedState state) {
        final var staging = this.updateService.stagingPath();
        if (staging.isEmpty()) {
            return;
        }
        final Path path = staging.get().resolve(STATE_FILE);
        final Path tmp = staging.get().resolve(STATE_FILE + ".tmp");
        try {
            Files.createDirectories(staging.get());
            this.objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException exception) {
            log.warn("自动更新状态写入失败（可忽略）：{}", exception.getMessage());
        }
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * 轮询状态（落盘在 {@code staging/feed-state.json}）：重启后不该忘记"这个版本装不上"。
     * <p>
     * 字段直接暴露给 Jackson（{@code fieldVisibility = ANY}）：这是纯内部状态文件，写一套
     * getter/setter 只会让 60 行变 200 行，而且手写映射容易漏字段导致"状态悄悄丢了"。
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class FeedState {

        /** 上一次检查时间（ISO-8601）。 */
        String lastCheckAt;

        /** 上一次检查是否成功。 */
        boolean lastCheckOk;

        /** 上一次失败原因。 */
        String lastError;

        /** 清单里看到的版本。 */
        String latestSeenVersion;

        /** 已经提交给外壳的版本。 */
        String lastStagedVersion;

        /** 提交时间。 */
        String lastStagedAt;

        /** 最近失败的版本。 */
        String failedVersion;

        /** 失败时间。 */
        String failedAt;
    }
}
