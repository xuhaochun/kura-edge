/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 JDK {@link HttpClient} 的更新包下载器（RFC 006 §2.4），不引入额外依赖。
 * <p>
 * 安全约束（本端点带鉴权且能落盘，按 S2 做威胁建模）：
 * </p>
 * <ul>
 *   <li>只接受 {@code http}/{@code https}，{@code file:}/{@code jar:} 一律拒——否则等于开放任意文件读。</li>
 *   <li>边下边算 sha256，且边下边数字节；超过 {@code maxBytes} 立即中止并删掉半截文件，
 *       不信任远端的 {@code Content-Length}。</li>
 *   <li>目标文件名完全由调用方决定（来自 request 的 {@code version}），本类不解析 URL 里的路径。</li>
 * </ul>
 */
@Component
public class HttpArtifactFetcher implements ArtifactFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpArtifactFetcher.class);

    /** 建立连接的上限。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 整次下载的上限（60 MB jar 在慢链路上也够）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    /** 读写缓冲区大小。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient client;

    /**
     * 生产构造器：使用 JDK 默认的 {@link HttpClient}（跟随重定向、10s 连接超时）。
     */
    public HttpArtifactFetcher() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * 测试用构造器：注入自定义客户端（便于用本地 HttpServer 覆盖成功/404/超限路径）。
     *
     * @param client HTTP 客户端
     */
    HttpArtifactFetcher(final HttpClient client) {
        this.client = client;
    }

    @Override
    public FetchedArtifact fetch(final String url, final Path partFile, final long maxBytes) {
        final URI uri = parseUri(url);
        createParent(partFile);
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/java-archive, application/octet-stream, */*")
                .GET()
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final IOException exception) {
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "下载更新包失败：" + uri + "（" + exception.getMessage() + "）", exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED, "下载更新包被中断：" + uri, exception);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                        "下载更新包失败：" + uri + " 返回 HTTP " + response.statusCode());
            }
            final OptionalLong declared = response.headers().firstValueAsLong("content-length");
            if (declared.isPresent() && declared.getAsLong() > maxBytes) {
                throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                        "更新包过大：" + declared.getAsLong() + " 字节（上限 " + maxBytes + "）");
            }
            return copyAndDigest(body, partFile, maxBytes);
        } catch (final IOException exception) {
            deleteQuietly(partFile);
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "读取更新包内容失败：" + uri + "（" + exception.getMessage() + "）", exception);
        }
    }

    /**
     * 校验并解析下载地址：只允许 http/https 且必须有主机名。
     *
     * @param url 原始地址
     * @return 解析后的 URI
     */
    static URI parseUri(final String url) {
        if (url == null || url.isBlank()) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID, "更新地址不能为空");
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (final URISyntaxException exception) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID, "更新地址不是合法 URI：" + url, exception);
        }
        final String scheme = Optional.ofNullable(uri.getScheme()).orElse("");
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "更新地址只允许 http/https，当前为 " + (scheme.isEmpty() ? "(无协议)" : scheme));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID, "更新地址缺少主机名：" + url);
        }
        return uri;
    }

    private FetchedArtifact copyAndDigest(final InputStream body, final Path partFile, final long maxBytes)
            throws IOException {
        final MessageDigest digest = sha256();
        long total = 0L;
        boolean complete = false;
        // 注意：不能只靠 Content-Length，服务端可能撒谎或不给（chunked），必须边下边数
        try (OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(partFile, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                            "更新包超过上限 " + maxBytes + " 字节，已中止下载");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            out.flush();
            complete = true;
        } finally {
            if (!complete) {
                deleteQuietly(partFile);
            }
        }
        fsync(partFile);
        final String hex = HexFormat.of().formatHex(digest.digest());
        log.info("更新包已下载：{}（{} 字节，sha256={}）", partFile, total, hex);
        return new FetchedArtifact(total, hex);
    }

    private static void createParent(final Path file) {
        final Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (final IOException exception) {
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "无法创建暂存目录 " + parent + "：" + exception.getMessage(), exception);
        }
    }

    private static void fsync(final Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (final IOException exception) {
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "更新包落盘失败 " + file + "：" + exception.getMessage(), exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            // SHA-256 是所有 JRE 的必备算法，走不到这里
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    static void deleteQuietly(final Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (final IOException exception) {
            log.warn("删除暂存文件失败，可忽略：{}（{}）", file, exception.getMessage());
        }
    }
}
