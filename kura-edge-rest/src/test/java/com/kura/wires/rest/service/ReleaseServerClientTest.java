/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.update.FeedManifestDto;

import org.junit.jupiter.api.Test;

/**
 * {@link ReleaseServerClient} 的解析与校验测试（Gherkin 风格）：清单地址怎么推导、清单怎么校验、
 * 相对地址怎么解析、按版本号取条目时的不一致怎么拒。
 */
class ReleaseServerClientTest {

    private static final String SERVER = "http://release.example:8081";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void derivesFeedUrlFromTheReleaseServerAddress() {
        final ReleaseServerClient client = givenClient(SERVER + "/", "");

        // 末尾斜杠要吃掉，否则会拼成 //feed.json
        assertThat(client.feedUrl()).isEqualTo(SERVER + "/feed.json");
        assertThat(client.releaseServerUrl()).isEqualTo(SERVER);
        assertThat(client.configured()).isTrue();
    }

    @Test
    public void explicitFeedUrlWinsOverTheServerAddress() {
        final ReleaseServerClient client = givenClient(SERVER, "http://nginx.example/releases/feed.json");

        // nginx/静态目录发版时给的是完整清单地址，它比"服务器地址 + feed.json"更精确
        assertThat(client.feedUrl()).isEqualTo("http://nginx.example/releases/feed.json");
    }

    @Test
    public void notConfiguredMeansNoFetchAtAll() {
        final ReleaseServerClient client = givenClient("", "");

        assertThat(client.configured()).isFalse();
        assertThat(client.latest()).isEmpty();
        assertThatThrownBy(client::fetch)
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_UNSUPPORTED));
    }

    @Test
    public void rejectsManifestWithUnsupportedProtocol() {
        final ReleaseServerClient client = clientWith("""
                {"protocol":2,"latest":{"version":"1.0.4","url":"kura-edge-1.0.4.jar","sha256":"a"}}
                """);

        assertThatThrownBy(client::fetch)
                .hasMessageContaining("协议版本不受支持");
    }

    @Test
    public void rejectsManifestWithoutLatestEntry() {
        final ReleaseServerClient client = clientWith("{\"protocol\":1}");

        assertThatThrownBy(client::fetch).hasMessageContaining("latest");
    }

    @Test
    public void resolvesRelativeJarUrlAgainstTheFeed() {
        final ReleaseServerClient client = clientWith("""
                {"protocol":1,"latest":{"version":"1.0.4","url":"kura-edge-1.0.4.jar","sha256":"a"}}
                """);
        final FeedManifestDto.Latest latest = client.latest().orElseThrow();

        assertThat(client.jarUrl(latest)).isEqualTo(SERVER + "/kura-edge-1.0.4.jar");
    }

    @Test
    public void requireAcceptsLatestOrAnythingWhenNoVersionGiven() {
        final ReleaseServerClient client = clientWith("""
                {"protocol":1,"latest":{"version":"1.0.4","url":"kura-edge-1.0.4.jar","sha256":"a"}}
                """);

        assertThat(client.require("").getVersion()).isEqualTo("1.0.4");
        assertThat(client.require(null).getVersion()).isEqualTo("1.0.4");
        assertThat(client.require("1.0.4").getVersion()).isEqualTo("1.0.4");
    }

    @Test
    public void requireRejectsVersionThatIsNotTheServerLatest() {
        final ReleaseServerClient client = clientWith("""
                {"protocol":1,"latest":{"version":"1.0.4","url":"kura-edge-1.0.4.jar","sha256":"a"}}
                """);

        assertThatThrownBy(() -> client.require("1.0.2"))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UPDATE_REQUEST_INVALID));
    }

    private ReleaseServerClient givenClient(final String serverUrl, final String feedUrl) {
        return new ReleaseServerClient(url -> "", this.objectMapper, serverUrl, feedUrl);
    }

    private ReleaseServerClient clientWith(final String manifest) {
        return new ReleaseServerClient(url -> manifest, this.objectMapper, SERVER, "");
    }
}
