/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 JDK {@link HttpClient} 的清单读取器（RFC 008）。
 * <p>
 * 三条硬约束，都是"被现场网络教过"的：
 * <ul>
 *   <li>只接受 http/https（复用 {@link HttpArtifactFetcher#parseUri} 的校验，{@code file:} 一律拒）；</li>
 *   <li>整次请求 10 s 超时——轮询线程不该被一台半死的文件服务器挂住；</li>
 *   <li>最多读 256 KiB：清单是几 KB，超出说明地址指错了（比如指到了 jar 本体），直接放弃。</li>
 * </ul>
 */
@Component
public class HttpManifestLoader implements ManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(HttpManifestLoader.class);

    /** 建立连接的上限。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 整次请求的上限。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 清单大小上限：正常只有几百字节。 */
    static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private final HttpClient client;

    /**
     * 创建读取器（每个实例一个 HttpClient，避免每次轮询新建连接池）。
     */
    public HttpManifestLoader() {
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(
                HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public String load(final String url) {
        final var uri = HttpArtifactFetcher.parseUri(url);
        final HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            final HttpResponse<byte[]> response = this.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                        "拉取更新清单失败：HTTP " + response.statusCode() + "（" + uri + "）");
            }
            final byte[] body = response.body();
            if (body.length == 0) {
                throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED, "更新清单为空（" + uri + "）");
            }
            if (body.length > MAX_MANIFEST_BYTES) {
                throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                        "更新清单超过 " + MAX_MANIFEST_BYTES + " 字节，地址可能指向了 jar 而不是清单（" + uri + "）");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED,
                    "拉取更新清单失败：" + exception.getMessage() + "（" + uri + "）", exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WiresException(ErrorCode.UPDATE_DOWNLOAD_FAILED, "拉取更新清单被中断", exception);
        }
    }
}
