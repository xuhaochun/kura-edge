/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.update.FeedManifestDto;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 发布服务器（{@code kura-edge-release-server}）的客户端：拿清单、算 jar 地址（RFC 010）。
 * <p>
 * 设备侧更新源现在**只有一个概念**：发布服务器地址（{@code kura.update.release-server-url}）。
 * 清单地址与 jar 地址都由它推导出来，不再要求运维手写完整的 {@code feed.json} 链接：
 * </p>
 * <ul>
 *   <li>清单：{@code <发布服务器>/feed.json}</li>
 *   <li>jar：清单里的相对地址按清单自身地址解析（发布服务器就是这么吐的）</li>
 * </ul>
 * <p>
 * 仍然保留 {@code kura.update.feed-url}：现场用 nginx/静态目录发版时直接指清单文件即可，
 * 两者都配时以显式 {@code feed-url} 为准（它是更精确的意图）。
 * </p>
 */
@Component
public class ReleaseServerClient {

    /** 清单在发布服务器上的固定路径。 */
    static final String FEED_PATH = "feed.json";

    /** 清单协议版本：与设备侧、发布服务器一致。 */
    static final int PROTOCOL = 1;

    private final ManifestLoader loader;

    private final ObjectMapper objectMapper;

    private final String releaseServerUrl;

    private final String feedUrl;

    /**
     * 创建客户端。
     *
     * @param loader 清单读取器
     * @param objectMapper JSON 序列化器
     * @param releaseServerUrl 发布服务器地址（如 {@code http://192.168.31.40:8081}），可留空
     * @param feedUrl 显式清单地址，可留空
     */
    @Autowired
    public ReleaseServerClient(final ManifestLoader loader, final ObjectMapper objectMapper,
            @Value("${kura.update.release-server-url:}") final String releaseServerUrl,
            @Value("${kura.update.feed-url:}") final String feedUrl) {
        this.loader = loader;
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.releaseServerUrl = trimTrailingSlash(releaseServerUrl);
        this.feedUrl = resolveFeedUrl(this.releaseServerUrl, feedUrl);
    }

    /**
     * 是否配置了更新源（发布服务器或显式清单地址）。
     *
     * @return true 表示可以拉清单
     */
    public boolean configured() {
        return !this.feedUrl.isEmpty();
    }

    /**
     * 清单地址（推导结果）。
     *
     * @return 绝对地址，未配置时为空串
     */
    public String feedUrl() {
        return this.feedUrl;
    }

    /**
     * 发布服务器地址（原样，用于界面展示）。
     *
     * @return 地址，未配置时为空串
     */
    public String releaseServerUrl() {
        return this.releaseServerUrl;
    }

    /**
     * 拉取并校验清单。
     *
     * @return 清单
     * @throws WiresException 未配置更新源、拉取失败、JSON 非法、协议不认或缺少 {@code latest}
     */
    public FeedManifestDto fetch() {
        if (!configured()) {
            throw new WiresException(ErrorCode.UPDATE_UNSUPPORTED,
                    "没有配置更新源：请在设备上设置 KURA_RELEASE_SERVER_URL（发布服务器地址）或 KURA_EDGE_UPDATE_FEED（清单地址）");
        }
        final FeedManifestDto manifest;
        try {
            manifest = this.objectMapper.readValue(this.loader.load(this.feedUrl), FeedManifestDto.class);
        } catch (final IOException exception) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "清单不是合法 JSON：" + exception.getMessage(), exception);
        }
        if (manifest.getLatest() == null) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "清单缺少 latest 字段（发布服务器上是不是还没有 jar？）");
        }
        if (manifest.getProtocol() != null && manifest.getProtocol() != PROTOCOL) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "清单协议版本不受支持：" + manifest.getProtocol() + "（本机只认 " + PROTOCOL + "）");
        }
        return manifest;
    }

    /**
     * 清单里的最新条目。
     *
     * @return 最新条目；未配置更新源时不拉取、直接返回空
     */
    public Optional<FeedManifestDto.Latest> latest() {
        if (!configured()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fetch().getLatest());
    }

    /**
     * 把清单条目解析成"要下载的 jar 地址"。
     *
     * @param latest 清单条目
     * @return 绝对地址
     * @throws WiresException 清单里没给 url
     */
    public String jarUrl(final FeedManifestDto.Latest latest) {
        final String url = latest.getUrl();
        if (url == null || url.isBlank()) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID, "清单里的 latest.url 为空");
        }
        return URI.create(this.feedUrl).resolve(url.trim()).toString();
    }

    /**
     * 按版本号取清单条目：不指定版本时取最新；指定了就必须与最新一致。
     * <p>
     * 清单是单指针（只描述"应该跑哪个版本"），所以请求一个更旧/不存在的版本时只能明确拒绝，
     * 而不是悄悄装成别的版本。
     * </p>
     *
     * @param version 目标版本，可留空
     * @return 清单条目
     * @throws WiresException 未配置更新源、指定版本与服务器最新不一致
     */
    public FeedManifestDto.Latest require(final String version) {
        final FeedManifestDto.Latest latest = latest()
                .orElseThrow(() -> new WiresException(ErrorCode.UPDATE_UNSUPPORTED,
                        "没有配置更新源：请设置 KURA_RELEASE_SERVER_URL 或 KURA_EDGE_UPDATE_FEED，或直接给出 jar 地址"));
        final String wanted = version == null ? "" : version.trim();
        if (!wanted.isEmpty() && !wanted.equals(latest.getVersion())) {
            throw new WiresException(ErrorCode.UPDATE_REQUEST_INVALID,
                    "发布服务器上最新是 " + latest.getVersion() + "，请求的是 " + wanted
                            + "（清单只描述最新版；要装其它版本请直接给出 jar 地址）");
        }
        return latest;
    }

    private static String resolveFeedUrl(final String serverUrl, final String explicitFeedUrl) {
        final String explicit = explicitFeedUrl == null ? "" : explicitFeedUrl.trim();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return serverUrl.isEmpty() ? "" : serverUrl + "/" + FEED_PATH;
    }

    private static String trimTrailingSlash(final String url) {
        final String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
