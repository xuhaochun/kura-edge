/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HttpArtifactFetcher} 的集成测试（Gherkin 风格）：用 JDK 自带的 {@code HttpServer} 起一个
 * 真实回环服务，覆盖成功下载、404、声明/实际超限、非法协议与非法 URI。
 * <p>
 * 这些路径无法用 mock 验证——"边下边算摘要、边下边数字节、失败删半截文件"都发生在真实 IO 上。
 * </p>
 */
class HttpArtifactFetcherTest {

    @TempDir
    Path root;

    private final HttpArtifactFetcher fetcher = new HttpArtifactFetcher();

    private HttpServer server;

    private Path partFile;

    @BeforeEach
    void setUp() {
        this.partFile = this.root.resolve("staging").resolve("kura-edge-1.0.4.jar.part");
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    @Test
    public void successfulDownloadWritesFileAndReturnsDigest() throws IOException {
        final byte[] payload = "PK\u0003\u0004real-jar-bytes".getBytes(StandardCharsets.UTF_8);
        givenServer(exchange -> respond(exchange, 200, payload, payload.length));

        final FetchedArtifact artifact = whenFetch(url("/kura-edge-1.0.4.jar"), 1024L * 1024L);

        assertThat(artifact.getSize()).isEqualTo(payload.length);
        assertThat(artifact.getSha256()).isEqualTo(sha256Hex(payload));
        assertThat(Files.readAllBytes(this.partFile)).isEqualTo(payload);
    }

    @Test
    public void failedDownloadDeletesPartialFile() throws IOException {
        givenServer(exchange -> respond(exchange, 404, new byte[0], 0));

        assertThatThrownBy(() -> whenFetch(url("/missing.jar"), 1024L * 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_DOWNLOAD_FAILED));

        assertThat(this.partFile).doesNotExist();
    }

    @Test
    public void downloadIsAbortedWhenDeclaredLengthExceedsLimit() throws IOException {
        final byte[] payload = new byte[4096];
        // 声明 4096 但只发 0 字节：中止必须发生在读流之前，服务端不配合也要拒
        givenServer(exchange -> respond(exchange, 200, payload, payload.length));

        assertThatThrownBy(() -> whenFetch(url("/big.jar"), 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));

        assertThat(this.partFile).doesNotExist();
    }

    @Test
    public void downloadIsAbortedWhenActualLengthExceedsLimit() throws IOException {
        final byte[] payload = new byte[8192];
        // 声明 -1（chunked，无 Content-Length）：只能靠边下边数
        givenServer(exchange -> respond(exchange, 200, payload, -1L));

        assertThatThrownBy(() -> whenFetch(url("/sneaky.jar"), 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));

        assertThat(this.partFile).doesNotExist();
    }

    @Test
    public void nonHttpSchemeIsRejectedBeforeAnyConnection() {
        assertThatThrownBy(() -> whenFetch("file:///etc/passwd", 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));

        assertThat(this.partFile).doesNotExist();
    }

    @Test
    public void malformedUrlIsRejected() {
        assertThatThrownBy(() -> whenFetch("not a url", 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));
    }

    @Test
    public void urlWithoutHostIsRejected() {
        assertThatThrownBy(() -> whenFetch("http:///kura-edge.jar", 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));
    }

    @Test
    public void blankUrlIsRejected() {
        assertThatThrownBy(() -> whenFetch("  ", 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));
    }

    @Test
    public void unreachablePortFailsAsDownloadError() throws IOException {
        // 先占一个端口再释放，确保没人监听：连接拒绝要映射成"下载失败"而不是崩出去
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        assertThatThrownBy(() -> whenFetch("http://127.0.0.1:" + port + "/kura-edge.jar", 1024L))
                .isInstanceOfSatisfying(WiresException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.UPDATE_DOWNLOAD_FAILED));
    }

    private void givenServer(final Consumer<HttpExchange> handler) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            try {
                handler.accept(exchange);
            } finally {
                exchange.close();
            }
        });
        this.server.start();
    }

    private String url(final String path) {
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + path;
    }

    private FetchedArtifact whenFetch(final String url, final long maxBytes) {
        return this.fetcher.fetch(url, this.partFile, maxBytes);
    }

    /**
     * 回一个响应：{@code declaredLength < 0} 走 chunked（无 {@code Content-Length}），
     * {@code 0} 表示无响应体，其余为固定长度。
     */
    private static void respond(final HttpExchange exchange, final int status, final byte[] body,
            final long declaredLength) {
        try {
            if (declaredLength < 0L) {
                exchange.sendResponseHeaders(status, 0L);
            } else if (declaredLength == 0L) {
                exchange.sendResponseHeaders(status, -1L);
                return;
            } else {
                exchange.sendResponseHeaders(status, declaredLength);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (final IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static String sha256Hex(final byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
