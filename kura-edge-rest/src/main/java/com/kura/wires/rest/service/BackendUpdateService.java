/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kura.wires.core.engine.WireGraphEngine;
import com.kura.wires.core.ha.GraphLeadership;
import com.kura.wires.update.FeedManifestDto;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.rest.dto.SystemVersionDto;
import com.kura.wires.rest.dto.UpdateAcceptedDto;
import com.kura.wires.rest.dto.UpdateRequestDto;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 热更新受理服务（RFC 006 §2.4）：下载并校验新 jar → 写 {@code staging/pending.json} → 延迟退出进程。
 * <p>
 * 后端**不自己换 jar**：运行中的 JVM 持有 jar 句柄（Windows 上无法覆盖），且换文件属于"安装器"的职责。
 * 这里的边界是——后端只把"一个已校验、可直接安装的 jar"放进 {@code staging/}，并用 {@code pending.json}
 * 通知外壳；真正停进程、改 {@code current.json}、重启 JVM 由桌面外壳完成。
 * </p>
 * <p>
 * 单一写者原则：{@code staging/} 只有后端写、{@code app/} 只有外壳写；本类对 {@code app/current.json}
 * 只读（用于 {@link #currentVersion()}）。
 * </p>
 * <p>
 * 蓝绿模式（RFC 007，PostgreSQL）下"退出"改成"移交"：{@code /update} 只落包 + 写 {@code pending.json}，
 * **不退出**——旧实例继续服务，外壳先把新实例以 standby 拉起并导航过去，再调 {@link #retire()}
 * 让旧实例停图、交出租约、优雅退出。这样页面全程不中断，图执行中断只有一次组件重建。
 * 文件 H2 单机模式仍走原来的"落包即退出"。
 * </p>
 */
@Service
public class BackendUpdateService {

    private static final Logger log = LoggerFactory.getLogger(BackendUpdateService.class);

    /** 后端侧下载上限：jar 约 60 MB，512 MiB 足够容错又不至于被异常大文件拖死。 */
    static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;

    /** 回执发出后到进程退出之间的延迟：给 HTTP 响应留出写出时间。 */
    static final Duration EXIT_DELAY = Duration.ofMillis(1500);

    /** 与外壳共享的文件协议版本（对应 {@code update-core::manifest::PROTOCOL}）。 */
    static final int PROTOCOL = 1;

    /**
     * 版本号白名单（与外壳 update-core 的 check_version 一致）：决定落地文件名，必须防路径穿越。
     * 包内可见，供 {@link UpdateFeedService} 在校验清单时复用同一套规则（两处不能各写一份）。
     */
    static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$");

    /** 蓝绿移交时留出的退出延迟：够 HTTP 202 写出，又尽量缩短"旧实例已停图、新实例还没接上"的窗口。 */
    static final Duration RETIRE_EXIT_DELAY = Duration.ofMillis(300);

    private static final String PENDING_FILE = "pending.json";

    private static final String CURRENT_FILE = "current.json";

    private final ArtifactFetcher fetcher;

    private final BackendExitHook exitHook;

    private final ObjectMapper objectMapper;

    private final ReleaseServerClient releaseServer;

    private final GraphLeadership leadership;

    private final WireGraphEngine engine;

    private final String stagingDir;

    private final String homeDir;

    private final Duration exitDelay;

    private final AtomicBoolean updating = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler;

    /**
     * 创建服务。
     *
     * @param fetcher 下载器
     * @param exitHook 进程退出钩子
     * @param objectMapper JSON 序列化器
     * @param releaseServer 发布服务器客户端（请求不带 url 时由它解析要装哪个版本）
     * @param stagingDir {@code KURA_EDGE_UPDATE_DIR}（后端独占写的暂存目录），非桌面运行时为空
     * @param homeDir {@code KURA_EDGE_HOME}（外壳独占写的 app 目录，本类只读取版本信息），非桌面运行时为空
     */
    // 两个构造器（生产 / 测试带退出延迟），必须显式标注，否则 Spring 会去找无参构造而启动失败
    @Autowired
    public BackendUpdateService(final ArtifactFetcher fetcher, final BackendExitHook exitHook,
            final ObjectMapper objectMapper, final ReleaseServerClient releaseServer,
            final GraphLeadership leadership, final WireGraphEngine engine,
            @Value("${kura.update.staging-dir:}") final String stagingDir,
            @Value("${kura.update.home-dir:}") final String homeDir) {
        this(fetcher, exitHook, objectMapper, releaseServer, leadership, engine, stagingDir, homeDir, EXIT_DELAY);
    }

    /**
     * 测试用构造器：可缩短退出延迟，避免单测空等。
     *
     * @param fetcher 下载器
     * @param exitHook 进程退出钩子
     * @param objectMapper JSON 序列化器
     * @param releaseServer 发布服务器客户端
     * @param leadership 图执行权选主（决定是否蓝绿移交）
     * @param engine 图引擎（移交前停图）
     * @param stagingDir 暂存目录，空串表示非桌面运行
     * @param homeDir app 目录，空串表示非桌面运行
     * @param exitDelay 退出延迟
     */
    BackendUpdateService(final ArtifactFetcher fetcher, final BackendExitHook exitHook,
            final ObjectMapper objectMapper, final ReleaseServerClient releaseServer,
            final GraphLeadership leadership, final WireGraphEngine engine,
            final String stagingDir, final String homeDir, final Duration exitDelay) {
        this.fetcher = fetcher;
        this.exitHook = exitHook;
        this.objectMapper = objectMapper;
        this.releaseServer = releaseServer;
        this.leadership = leadership;
        this.engine = engine;
        this.stagingDir = stagingDir == null ? "" : stagingDir;
        this.homeDir = homeDir == null ? "" : homeDir;
        this.exitDelay = exitDelay;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "kura-edge-update-exit");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 返回当前运行的版本信息：桌面运行时读外壳写的 {@code app/current.json}，否则回退到 jar 清单版本。
     *
     * @return 版本信息
     */
    public SystemVersionDto currentVersion() {
        final Path state = homePath(CURRENT_FILE);
        if (state != null && Files.isRegularFile(state)) {
            try {
                final JsonNode node = this.objectMapper.readTree(Files.readAllBytes(state));
                return new SystemVersionDto(text(node, "version", "unknown"), text(node, "jar", ""),
                        text(node, "sha256", ""), "desktop", role(), isBlueGreen());
            } catch (final IOException | RuntimeException exception) {
                log.warn("读取 {} 失败，回退为开发版本信息：{}", state, exception.getMessage());
            }
        }
        return new SystemVersionDto(manifestVersion(), "", "", "maven", role(), isBlueGreen());
    }

    /**
     * 受理一次热更新：下载 → 校验摘要 → 落 {@code pending.json} → 延迟退出。
     * <p>
     * 这里**不**先探测"新版本能否起得来"——离线环境下无法可靠预判；由外壳的启动探测 + 自动回滚兜底。
     * </p>
     *
     * <p>
     * 请求里的 {@code url} 可以留空：此时按版本号（也可以不给，等于"最新版"）到发布服务器清单里解析
     * 下载地址与 sha256——界面上的"一键升级到服务器最新版"走的就是这条（RFC 010）。
     * </p>
     *
     * @param request 更新请求（字段已过 Bean Validation）
     * @return 受理回执
     * @throws WiresException 非桌面运行（{@code UPDATE_UNSUPPORTED}）、已有更新在进行
     *         （{@code UPDATE_IN_PROGRESS}）、下载失败（{@code UPDATE_DOWNLOAD_FAILED}）、
     *         包非法（{@code UPDATE_REQUEST_INVALID}）
     */
    public UpdateAcceptedDto requestUpdate(final UpdateRequestDto request) {
        final Path staging = requireDesktopStaging();
        if (isBlueGreen() && !this.leadership.isLeader()) {
            throw new WiresException(ErrorCode.UPDATE_UNSUPPORTED,
                    "本实例是 standby（未持有图执行权），请对正在服务的实例发起更新");
        }
        if (!this.updating.compareAndSet(false, true)) {
            throw new WiresException(ErrorCode.UPDATE_IN_PROGRESS, "已有更新在进行，请等本次更新结束后再试");
        }
        boolean handling = false;
        try {
            final ReleaseSource source = resolveSource(request);
            final String version = source.version();
            final String expectedSha = source.sha256();
            final String fileName = "kura-edge-" + version + ".jar";
            final Path part = staging.resolve(fileName + ".part");
            final Path target = staging.resolve(fileName);
            deleteLeftoverParts(staging);
            final FetchedArtifact artifact = this.fetcher.fetch(source.url(), part, MAX_DOWNLOAD_BYTES);
            if (expectedSha != null && !artifact.getSha256().equalsIgnoreCase(expectedSha)) {
                HttpArtifactFetcher.deleteQuietly(part);
                throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                        "下载内容的 sha256 与请求不符（期望 " + expectedSha
                                + "，实际 " + artifact.getSha256() + "），已丢弃");
            }
            if (expectedSha == null) {
                log.warn("热更新请求未带 sha256，按实际下载到的摘要 {} 认定（建议带上以防中间人篡改）",
                        artifact.getSha256());
            }
            moveIntoPlace(part, target);
            writePending(staging, fileName, version, artifact);
            handling = true;
            if (isBlueGreen()) {
                // 蓝绿：本进程继续服务，等外壳起好新实例并导航过去后再 /retire 移交；
                // 标志位当场放开——移交之前运维若想重发请求，走的是同一套"先换包再移交"流程。
                this.updating.set(false);
                log.info("热更新包已就绪：{}（{} 字节），蓝绿模式：等待外壳拉起新实例后由 /retire 移交",
                        target, artifact.getSize());
            } else {
                scheduleExit();
                log.info("热更新包已就绪：{}（{} 字节），{} ms 后就地退出重启进程", target, artifact.getSize(),
                        this.exitDelay.toMillis());
            }
            return new UpdateAcceptedDto(version, fileName, artifact.getSha256());
        } finally {
            if (!handling) {
                // 失败后放开标志位，运维可以直接重发请求；成功则流程继续（退出或等待移交）
                this.updating.set(false);
            }
        }
    }

    /**
     * 决定这次从哪儿下、下哪个版本、期望什么摘要。
     * <p>
     * 两条来源：显式 {@code url}（原样使用，摘要可留空）或**发布服务器清单**（地址与摘要都取清单里的，
     * 因此摘要必然有值——比手填更不容易错）。
     * </p>
     *
     * @param request 更新请求
     * @return 下载来源
     */
    private ReleaseSource resolveSource(final UpdateRequestDto request) {
        final String explicitUrl = request.getUrl() == null ? "" : request.getUrl().trim();
        if (!explicitUrl.isEmpty()) {
            return new ReleaseSource(explicitUrl, normalizeSha(request.getSha256()), resolveVersion(request));
        }
        final FeedManifestDto.Latest latest = this.releaseServer.require(request.getVersion());
        final String version = latest.getVersion() == null ? "" : latest.getVersion();
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID, "发布服务器清单里的版本号非法：" + version);
        }
        final String sha256 = normalizeSha(latest.getSha256());
        if (sha256 == null) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "发布服务器清单缺少 sha256（" + version + "），拒绝安装");
        }
        return new ReleaseSource(this.releaseServer.jarUrl(latest), sha256, version);
    }

    /**
     * 一次下载的来源：地址、期望摘要（可空）与落地版本号。
     *
     * @param url jar 地址
     * @param sha256 期望摘要，{@code null} 表示不校验
     * @param version 落地版本号
     */
    private record ReleaseSource(String url, String sha256, String version) {
    }

    /**
     * 是否具备零中断的蓝绿移交能力（PostgreSQL 咨询锁可用）。
     *
     * @return true 表示 {@code /update} 不会立刻退出进程，而是等 {@link #retire()} 移交
     */
    public boolean isBlueGreen() {
        return this.leadership != null && this.leadership.isBlueGreen();
    }

    /**
     * 蓝绿移交（RFC 007）：停图 → 交出租约 → 优雅退出，新实例随即接管线图。
     * <p>
     * 顺序不能反：先 {@code pauseGraph()} 停掉一切 tick 再放锁，否则新实例抢到锁开始跑图时，
     * 旧实例的定时器还在往总线上送数据——同一台设备上两个实例重复采集/重复上报。
     * </p>
     *
     * @throws WiresException 非蓝绿模式（{@code UPDATE_UNSUPPORTED}）
     */
    public void retire() {
        if (!isBlueGreen()) {
            throw new WiresException(ErrorCode.UPDATE_UNSUPPORTED,
                    "当前是单机模式（文件 H2），不支持蓝绿移交；请等进程自行退出或直接重启");
        }
        this.engine.pauseGraph();
        this.leadership.release();
        log.info("蓝绿移交：已停图并交出执行权，{} ms 后退出（新实例会在此之前接管线图）",
                RETIRE_EXIT_DELAY.toMillis());
        this.scheduler.schedule(() -> {
            try {
                this.exitHook.exit();
            } catch (final RuntimeException exception) {
                log.error("蓝绿移交：进程退出失败，需人工重启", exception);
            }
        }, RETIRE_EXIT_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String role() {
        if (this.leadership == null) {
            return "leader";
        }
        return this.leadership.isLeader() ? "leader" : "standby";
    }

    /**
     * 判断当前是否处于桌面运行时（两个目录都配置了）。
     *
     * @return true 表示可以热更新
     */
    public boolean supportsHotUpdate() {
        return !this.stagingDir.isBlank() && !this.homeDir.isBlank();
    }

    /**
     * 暂存目录路径（可能为空，调用方须先 {@link #supportsHotUpdate()}）。
     *
     * @return 暂存目录，未配置时为空
     */
    Optional<Path> stagingPath() {
        return this.stagingDir.isBlank() ? Optional.empty() : Optional.of(Paths.get(this.stagingDir));
    }

    /**
     * app 目录下某个文件的路径，未配置 {@code KURA_EDGE_HOME} 时返回 {@code null}。
     *
     * @param name 文件名
     * @return 路径或 {@code null}
     */
    Path homePath(final String name) {
        return this.homeDir.isBlank() ? null : Paths.get(this.homeDir).resolve(name);
    }

    /**
     * 解析目标版本号：请求里给了就用（已过 Bean Validation），没给就从 URL 文件名推导。
     * <p>
     * 推导规则对应构建脚本的产物名 {@code kura-edge-<版本>.jar}，界面上的"只填网址"就靠它。
     * </p>
     *
     * @param request 更新请求
     * @return 合法版本号
     * @throws WiresException 无法从 URL 推导出合法版本号（{@code UPDATE_REQUEST_INVALID}）
     */
    private static String resolveVersion(final UpdateRequestDto request) {
        final String given = request.getVersion() == null ? "" : request.getVersion().trim();
        if (!given.isEmpty()) {
            // 防御性校验：/update 走 Bean Validation，但清单轮询是进程内调用，不经过那层
            if (!VERSION_PATTERN.matcher(given).matches()) {
                throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID, "版本号非法：" + given);
            }
            return given;
        }
        final String path;
        try {
            path = Optional.ofNullable(URI.create(request.getUrl()).getPath()).orElse("");
        } catch (final IllegalArgumentException exception) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "URL 无法解析，请在请求里显式给出 version：" + exception.getMessage(), exception);
        }
        final String name = path.substring(path.lastIndexOf('/') + 1);
        final String base = name.endsWith(".jar") ? name.substring(0, name.length() - ".jar".length()) : name;
        final String derived = base.startsWith("kura-edge-") ? base.substring("kura-edge-".length()) : base;
        if (!VERSION_PATTERN.matcher(derived).matches()) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "无法从 URL 的文件名推导版本号（应为 kura-edge-<版本>.jar，实际 '" + name
                            + "'），请在请求里显式给出 version");
        }
        return derived;
    }

    /**
     * 归一化 sha256：空/空白 → {@code null} 表示"不校验，用实际下载到的摘要"。
     *
     * @param sha256 请求里的摘要
     * @return 64 位小写十六进制，或 {@code null}
     */
    private static String normalizeSha(final String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        return sha256.trim().toLowerCase(Locale.ROOT);
    }

    private Path requireDesktopStaging() {
        if (!supportsHotUpdate()) {
            throw new WiresException(ErrorCode.UPDATE_UNSUPPORTED,
                    "当前不是桌面运行时（缺少 KURA_EDGE_UPDATE_DIR / KURA_EDGE_HOME 环境变量），无法热更新");
        }
        final Path staging = Paths.get(this.stagingDir);
        try {
            Files.createDirectories(staging);
        } catch (final IOException exception) {
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "无法创建暂存目录 " + staging + "：" + exception.getMessage(), exception);
        }
        return staging;
    }

    /**
     * 清掉上一次失败留下的 {@code *.part}：外壳不认半截文件，留着只占磁盘还会误导排障。
     *
     * @param staging 暂存目录
     */
    private void deleteLeftoverParts(final Path staging) {
        try (var entries = Files.list(staging)) {
            entries.filter(path -> path.getFileName().toString().endsWith(".part"))
                    .forEach(HttpArtifactFetcher::deleteQuietly);
        } catch (final IOException exception) {
            log.warn("清理暂存目录 {} 的残留文件失败，可忽略：{}", staging, exception.getMessage());
        }
    }

    private void moveIntoPlace(final Path part, final Path target) {
        try {
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException exception) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException exception) {
            HttpArtifactFetcher.deleteQuietly(part);
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "无法把更新包就位到 " + target + "：" + exception.getMessage(), exception);
        }
    }

    /**
     * 原子写 {@code staging/pending.json}：外壳靠它触发应用，写到一半的 JSON 必须不可见。
     *
     * @param staging 暂存目录
     * @param fileName 目标 jar 文件名
     * @param version 版本号
     * @param artifact 已落盘的 jar 摘要
     */
    private void writePending(final Path staging, final String fileName, final String version,
            final FetchedArtifact artifact) {
        final ObjectNode node = this.objectMapper.createObjectNode();
        node.put("protocol", PROTOCOL);
        node.put("version", version);
        node.put("jar", fileName);
        node.put("sha256", artifact.getSha256());
        node.put("size", artifact.getSize());
        node.put("requestedAt", Instant.now().toString());
        final Path pending = staging.resolve(PENDING_FILE);
        final Path tmp = staging.resolve(PENDING_FILE + ".tmp");
        try {
            this.objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), node);
            try {
                Files.move(tmp, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException exception) {
                Files.move(tmp, pending, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException exception) {
            HttpArtifactFetcher.deleteQuietly(tmp);
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "无法写入 " + pending + "：" + exception.getMessage(), exception);
        }
    }

    private void scheduleExit() {
        this.scheduler.schedule(() -> {
            try {
                this.exitHook.exit();
            } catch (final RuntimeException exception) {
                log.error("热更新：进程退出失败，需人工重启", exception);
            }
        }, this.exitDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String text(final JsonNode node, final String field, final String fallback) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static String manifestVersion() {
        return Optional.ofNullable(BackendUpdateService.class.getPackage().getImplementationVersion())
                .orElse("dev");
    }
}
