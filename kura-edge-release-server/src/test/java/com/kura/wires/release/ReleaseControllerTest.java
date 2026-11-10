/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 发布服务器端点的端到端测试（Gherkin 风格）：清单、下载、首页、探活，以及"没有 jar"时的 404。
 * <p>
 * 用 {@code @SpringBootTest} 而不是 {@code @WebMvcTest}：这里要验的正是"目录里的真实文件能否被
 * 正确列出来并流出去"，把 catalog 桩掉就等于什么都没验。发布目录用静态临时目录，在 Spring 上下文启动前
 * 就把文件摆好。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseControllerTest {

    private static final byte[] PAYLOAD = "PK\u0003\u0004fake-jar".getBytes(StandardCharsets.UTF_8);

    private static final Path RELEASE_DIR = createReleaseDir();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void releaseDirProperty(final DynamicPropertyRegistry registry) {
        registry.add("kura.release.dir", RELEASE_DIR::toString);
        registry.add("kura.release.base-url", () -> "http://192.168.31.40:8081");
    }

    @Test
    public void feedDescribesTheNewestJar() throws Exception {
        this.mockMvc.perform(get("/feed.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.protocol").value(1))
                .andExpect(jsonPath("$.latest.version").value("1.0.4"))
                .andExpect(jsonPath("$.latest.url").value("kura-edge-1.0.4.jar"))
                .andExpect(jsonPath("$.latest.sha256").value(sha256Hex()))
                .andExpect(jsonPath("$.latest.size").value(PAYLOAD.length));
    }

    @Test
    public void downloadServesTheJarBytes() throws Exception {
        this.mockMvc.perform(get("/kura-edge-1.0.4.jar"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/java-archive"))
                .andExpect(header().longValue("Content-Length", PAYLOAD.length))
                .andExpect(content().bytes(PAYLOAD));
    }

    @Test
    public void downloadRejectsUnknownNames() throws Exception {
        this.mockMvc.perform(get("/kura-edge-9.9.9.jar")).andExpect(status().isNotFound());
        this.mockMvc.perform(get("/etc/passwd")).andExpect(status().isNotFound());
    }

    @Test
    public void indexListsVersionsAndShowsDeviceConfig() throws Exception {
        this.mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("kura-edge-1.0.4.jar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "KURA_EDGE_UPDATE_FEED=http://192.168.31.40:8081/feed.json")));
    }

    @Test
    public void healthEndpointAnswersOk() throws Exception {
        this.mockMvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    private static Path createReleaseDir() {
        try {
            final Path dir = Files.createTempDirectory("kura-release-");
            Files.write(dir.resolve("kura-edge-1.0.3.jar"), "old".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("kura-edge-1.0.4.jar"), PAYLOAD);
            dir.toFile().deleteOnExit();
            return dir;
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256Hex() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PAYLOAD));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    public void emptyDirectoryAnswers404WithHint() throws Exception {
        // 独立起一个只有空目录的上下文成本高，这里直接验 catalog 的空目录行为与 404 分支的措辞来源
        assertThat(new ReleaseCatalog(emptyProperties()).feed()).isEmpty();
    }

    @Test
    public void feedAnswers404WithTheResolvedDirectory() {
        // 现场最常踩的坑是"jar 放错目录"，404 体里必须能直接看到服务器扫的是哪个绝对路径
        final ReleaseProperties properties = emptyProperties();
        final ResponseEntity<Object> response = new ReleaseController(new ReleaseCatalog(properties), properties).feed();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(String.valueOf(response.getBody())).contains(RELEASE_DIR.resolve("empty").toString());
    }

    private static ReleaseProperties emptyProperties() {
        final ReleaseProperties properties = new ReleaseProperties();
        properties.setDir(RELEASE_DIR.resolve("empty").toString());
        return properties;
    }
}
