/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import com.kura.wires.update.FeedManifestDto;
import com.kura.wires.update.VersionComparator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 发布目录索引（RFC 009）：扫 {@code releases/} 目录里的 {@code kura-edge-<版本>.jar}，
 * 算出每个 jar 的 sha256/大小，并生成设备要的 {@code feed.json}。
 * <p>
 * 两条设计约束：
 * </p>
 * <ul>
 *   <li>**文件名即版本**：只有严格匹配 {@code kura-edge-<版本>.jar} 的文件才算发布物，其余（临时文件、
 *       手滑改了名的、{@code .part} 半截下载）一律忽略——设备侧对文件名也有同样的白名单校验，
 *       两边不一致会表现为"清单里有、设备拒收"；</li>
 *   <li>**摘要带缓存**：60 MB 的 jar 每次请求都重算 sha256 太浪费，按（路径, 大小, mtime）缓存，
 *       重新拷一份同名 jar 进来（mtime 变）会自动失效。</li>
 * </ul>
 */
@Service
public class ReleaseCatalog {

    private static final Logger log = LoggerFactory.getLogger(ReleaseCatalog.class);

    /** 发布物命名前缀。 */
    static final String PREFIX = "kura-edge-";

    /** 发布物后缀。 */
    static final String SUFFIX = ".jar";

    /** 与设备侧 {@code BackendUpdateService.VERSION_PATTERN}、外壳 {@code check_version} 保持一致的版本白名单。 */
    static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$");

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path dir;

    private final ConcurrentMap<Path, CachedDigest> digests = new ConcurrentHashMap<>();

    /**
     * 创建索引。
     *
     * @param properties 发布配置
     */
    public ReleaseCatalog(final ReleaseProperties properties) {
        this.dir = resolveDirectory(properties.getDir(), Paths.get("").toAbsolutePath(), locateCodeBase());
        log.info("发布目录：{}（配置值 {}）", this.dir, properties.getDir());
    }

    /**
     * 把配置里的目录解析成绝对路径。
     * <p>
     * 为什么不是简单地 {@code cwd.resolve(dir)}：同一个程序有两种常见的启动方式，工作目录不同——
     * {@code cd kura-edge-release-server && java -jar target/x.jar}（cwd = 模块目录），与**在 IDEA 里直接 Run、
     * 或在仓库根 {@code java -jar kura-edge-release-server/target/x.jar}**（cwd = 项目根）。
     * 后者会让相对路径 {@code releases} 指向 {@code <仓库根>/releases}（不存在），表现为首页提示
     * "目录里还没有 jar"、设备拉清单 404。所以按顺序找：cwd → 代码所在模块根。
     * </p>
     *
     * @param configured 配置值（可为相对路径）
     * @param cwd 工作目录（绝对）
     * @param codeBase 代码位置（{@code target/classes} 或 {@code target/<jar>}），可为 {@code null}
     * @return 目录绝对路径
     */
    static Path resolveDirectory(final String configured, final Path cwd, final Path codeBase) {
        final Path raw = Paths.get(configured == null || configured.isBlank() ? "releases" : configured.trim());
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        final Path fromCwd = cwd.resolve(raw).normalize();
        if (Files.isDirectory(fromCwd)) {
            return fromCwd;
        }
        final Optional<Path> moduleRoot = moduleRootOf(codeBase);
        if (moduleRoot.isPresent()) {
            final Path fromModule = moduleRoot.get().toAbsolutePath().normalize().resolve(raw).normalize();
            if (Files.isDirectory(fromModule)) {
                log.info("工作目录 {} 下没有 {}，改用代码同级的 {}", cwd, raw, fromModule);
                return fromModule;
            }
        }
        return fromCwd;
    }

    /**
     * 从代码位置推出模块根：只认标准 Maven 输出布局 {@code <模块>/target/classes}、{@code <模块>/target/<jar>}
     * （上两级即模块根）。独立部署、摆在任意目录的 {@code app.jar} 不做推断，免得认到无关的上级目录。
     *
     * @param codeBase 代码位置，可为 {@code null}
     * @return 模块根；非标准布局时为空
     */
    static Optional<Path> moduleRootOf(final Path codeBase) {
        if (codeBase == null) {
            return Optional.empty();
        }
        final Path target = codeBase.getParent();
        if (target == null || !target.endsWith("target") || target.getParent() == null) {
            return Optional.empty();
        }
        return Optional.of(target.getParent());
    }

    /**
     * 代码位置：开发态是 {@code target/classes}，{@code java -jar} 时 Spring Boot 的嵌套类加载器给的是
     * {@code jar:file:/…/app.jar!/BOOT-INF/classes!/} 这种转不成 {@link Path} 的位置，所以再退回 {@code java.class.path}。
     *
     * @return 代码位置，推断不出来时为 {@code null}
     */
    private static Path locateCodeBase() {
        final Path fromCodeSource = locateFromCodeSource();
        return fromCodeSource != null ? fromCodeSource : locateFromClassPath();
    }

    private static Path locateFromCodeSource() {
        try {
            final CodeSource source = ReleaseCatalog.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null || !"file".equals(source.getLocation().getProtocol())) {
                return null;
            }
            return Paths.get(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (final Exception exception) {
            return null;
        }
    }

    private static Path locateFromClassPath() {
        final String[] entries = System.getProperty("java.class.path", "").split(File.pathSeparator);
        for (final String entry : entries) {
            if (entry.isBlank()) {
                continue;
            }
            // 从仓库根敲 java -jar kura-edge-release-server/target/x.jar 时，classpath 里是相对路径
            final Path path = Paths.get(entry).toAbsolutePath().normalize();
            final Path parent = path.getParent();
            if (parent != null && parent.endsWith("target")) {
                // 这条 entry 本身就是 <模块>/target/classes 或 <模块>/target/<jar>，正是 moduleRootOf 要的形状
                return path;
            }
        }
        return null;
    }

    /**
     * 返回发布目录。
     *
     * @return 绝对路径
     */
    public Path directory() {
        return this.dir;
    }

    /**
     * 列出全部发布物，按版本从新到旧。
     *
     * @return 发布物列表（目录不存在时为空）
     */
    public List<Artifact> list() {
        if (!Files.isDirectory(this.dir)) {
            log.warn("发布目录 {} 不存在：请把 kura-edge-<版本>.jar 放进去（build-desktop.sh 会自动拷）", this.dir);
            return List.of();
        }
        final List<Artifact> artifacts = new ArrayList<>();
        try (Stream<Path> entries = Files.list(this.dir)) {
            entries.filter(Files::isRegularFile).forEach(path -> toArtifact(path).ifPresent(artifacts::add));
        } catch (final IOException exception) {
            log.warn("扫描发布目录 {} 失败：{}", this.dir, exception.getMessage());
            return List.of();
        }
        artifacts.sort(Comparator.comparing(Artifact::version, VersionComparator::compare).reversed());
        return artifacts;
    }

    /**
     * 最新发布物（版本号最大者）。
     *
     * @return 发布物，目录为空时为空
     */
    public Optional<Artifact> latest() {
        final List<Artifact> artifacts = list();
        return artifacts.isEmpty() ? Optional.empty() : Optional.of(artifacts.get(0));
    }

    /**
     * 按文件名精确查找（下载接口用：只认索引里的名字，天然挡掉路径穿越）。
     *
     * @param fileName 文件名
     * @return 发布物
     */
    public Optional<Artifact> find(final String fileName) {
        return list().stream().filter(artifact -> artifact.fileName().equals(fileName)).findFirst();
    }

    /**
     * 生成设备用的更新清单。
     *
     * @return 清单，目录为空时为空
     */
    public Optional<FeedManifestDto> feed() {
        return latest().map(artifact -> {
            final FeedManifestDto.Latest latest = new FeedManifestDto.Latest();
            latest.setVersion(artifact.version());
            // 相对地址：设备按清单自身地址解析，所以服务器搬到哪个 IP/域名都不用改清单
            latest.setUrl(artifact.fileName());
            latest.setSha256(artifact.sha256());
            latest.setSize(artifact.size());
            latest.setPublishedAt(artifact.modifiedAt());
            final FeedManifestDto manifest = new FeedManifestDto();
            manifest.setProtocol(ReleaseCatalog.PROTOCOL);
            manifest.setLatest(latest);
            return manifest;
        });
    }

    /**
     * 清单协议版本：与设备侧 {@code UpdateFeedService.PROTOCOL} 一致。
     */
    static final int PROTOCOL = 1;

    private Optional<Artifact> toArtifact(final Path path) {
        final String fileName = path.getFileName().toString();
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        final String version = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
        if (!VERSION_PATTERN.matcher(version).matches()) {
            log.warn("忽略 {}：文件名里的版本号 {} 不合规（应为 kura-edge-<字母数字. _ ->.jar）", fileName, version);
            return Optional.empty();
        }
        try {
            final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return Optional.of(new Artifact(version, fileName, attributes.size(), digestOf(path, attributes),
                    attributes.lastModifiedTime().toInstant().toString(), path));
        } catch (final IOException exception) {
            log.warn("读取 {} 失败，忽略：{}", path, exception.getMessage());
            return Optional.empty();
        }
    }

    private String digestOf(final Path path, final BasicFileAttributes attributes) throws IOException {
        final CachedDigest cached = this.digests.get(path);
        final long modified = attributes.lastModifiedTime().toMillis();
        if (cached != null && cached.size == attributes.size() && cached.modifiedMillis == modified) {
            return cached.sha256;
        }
        final String sha256 = sha256(path);
        this.digests.put(path, new CachedDigest(attributes.size(), modified, sha256));
        return sha256;
    }

    private static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
        final byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 摘要缓存条目：大小 + mtime 变了就重算。
     *
     * @param size 字节数
     * @param modifiedMillis 最后修改时间（毫秒）
     * @param sha256 摘要
     */
    private record CachedDigest(long size, long modifiedMillis, String sha256) {
    }
}
