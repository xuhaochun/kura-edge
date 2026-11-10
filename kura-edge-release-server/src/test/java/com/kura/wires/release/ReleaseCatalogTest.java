/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.kura.wires.update.FeedManifestDto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ReleaseCatalog} 的扫描/选新/摘要行为测试（Gherkin 风格）。
 * <p>
 * 这一层是发布服务器的"真相来源"：设备装错版本的现场事故，十次有九次是清单指错了 jar。
 * 所以这里重点验三件事——**只认规范文件名**、**按版本语义选最新**、**摘要与文件内容一致**。
 */
class ReleaseCatalogTest {

    @TempDir
    Path dir;

    @Test
    public void listsOnlyCorrectlyNamedJarsNewestFirst() throws IOException {
        writeJar("kura-edge-1.0.3.jar", "old");
        writeJar("kura-edge-1.0.10.jar", "newest");
        writeJar("kura-edge-1.0.9.jar", "middle");
        writeJar("notes.txt", "ignore me");
        writeJar("kura-edge-bad name.jar", "space in version");
        writeJar("kura-edge-1.0.4.jar.part", "half download");
        final ReleaseCatalog catalog = givenCatalog();

        final List<Artifact> artifacts = catalog.list();

        // 1.0.10 必须排在 1.0.9 前面：字符串排序会把它们反着排
        assertThat(artifacts).extracting(Artifact::version).containsExactly("1.0.10", "1.0.9", "1.0.3");
        assertThat(catalog.latest()).map(Artifact::version).contains("1.0.10");
    }

    @Test
    public void feedPointsAtTheNewestJarWithMatchingDigest() throws IOException {
        writeJar("kura-edge-1.0.3.jar", "old");
        final byte[] payload = "newest".getBytes(StandardCharsets.UTF_8);
        writeJar("kura-edge-1.0.4.jar", payload);

        final FeedManifestDto manifest = givenCatalog().feed().orElseThrow();

        assertThat(manifest.getProtocol()).isEqualTo(ReleaseCatalog.PROTOCOL);
        assertThat(manifest.getLatest().getVersion()).isEqualTo("1.0.4");
        // 相对地址：服务器换 IP/域名都不用改清单
        assertThat(manifest.getLatest().getUrl()).isEqualTo("kura-edge-1.0.4.jar");
        assertThat(manifest.getLatest().getSha256()).isEqualTo(sha256Hex(payload));
        assertThat(manifest.getLatest().getSize()).isEqualTo(payload.length);
    }

    @Test
    public void digestIsRecomputedWhenTheSameFileIsReplaced() throws IOException {
        writeJar("kura-edge-1.0.4.jar", "first");
        final ReleaseCatalog catalog = givenCatalog();
        final String first = catalog.latest().orElseThrow().sha256();

        // 同名覆盖（构建脚本 cp -f 的常见动作）：内容变了摘要必须变，否则设备会拒收
        Files.write(this.dir.resolve("kura-edge-1.0.4.jar"), "second".getBytes(StandardCharsets.UTF_8));
        final String second = catalog.latest().orElseThrow().sha256();

        assertThat(second).isNotEqualTo(first);
        assertThat(second).isEqualTo(sha256Hex("second".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void findAcceptsOnlyNamesThatExist() throws IOException {
        writeJar("kura-edge-1.0.4.jar", "payload");
        final ReleaseCatalog catalog = givenCatalog();

        assertThat(catalog.find("kura-edge-1.0.4.jar")).isPresent();
        // 路径穿越/任意文件读由"只认索引里的名字"天然挡掉
        assertThat(catalog.find("../../etc/passwd")).isEmpty();
        assertThat(catalog.find("kura-edge-1.0.5.jar")).isEmpty();
    }

    @Test
    public void emptyOrMissingDirectoryYieldsNothingInsteadOfFailing() {
        assertThat(givenCatalog()).isNotNull();
        final ReleaseCatalog missing = new ReleaseCatalog(properties(this.dir.resolve("does-not-exist")));

        assertThat(missing.list()).isEmpty();
        assertThat(missing.latest()).isEmpty();
        assertThat(missing.feed()).isEmpty();
    }

    /**
     * 回归用例：IDEA 里 Run 时工作目录是项目根，相对 {@code releases} 必须回退到"代码同级"的模块目录，
     * 否则发布服务器会对着一个不存在的目录报"还没有 jar"，而现场看到的就是设备拉清单 404。
     */
    @Test
    public void relativeDirectoryFallsBackToTheModuleRootWhenWorkingDirectoryDiffers() throws IOException {
        final Path moduleRoot = Files.createDirectories(this.dir.resolve("module"));
        final Path releases = Files.createDirectories(moduleRoot.resolve("releases"));
        Files.write(releases.resolve("kura-edge-2.0.0.jar"), "PK".getBytes(StandardCharsets.UTF_8));
        // 模拟 IDEA：cwd = 仓库根（下面的 releases 不存在），codeBase = <module>/target/classes
        final Path repoRoot = Files.createDirectories(this.dir.resolve("repo"));
        final Path codeBase = Files.createDirectories(moduleRoot.resolve("target/classes"));

        final Path resolved = ReleaseCatalog.resolveDirectory("releases", repoRoot, codeBase);

        assertThat(resolved).isEqualTo(releases);
        assertThat(new ReleaseCatalog(properties(resolved)).latest()).map(Artifact::version).contains("2.0.0");
    }

    @Test
    public void moduleRootIsDerivedFromTheMavenTargetLayout() throws IOException {
        Files.createDirectories(this.dir.resolve("module/target/classes"));

        assertThat(ReleaseCatalog.moduleRootOf(this.dir.resolve("module/target/classes")))
                .contains(this.dir.resolve("module"));
        assertThat(ReleaseCatalog.moduleRootOf(this.dir.resolve("module/target/kura-edge-release-server-1.0.jar")))
                .contains(this.dir.resolve("module"));
    }

    @Test
    public void moduleRootIsUnknownForStandaloneJarLayouts() {
        // 独立部署（例如 /opt/release-server/app.jar）：不做推断，回退保持"按工作目录"的老行为
        assertThat(ReleaseCatalog.moduleRootOf(this.dir.resolve("opt/release-server/app.jar"))).isEmpty();
        assertThat(ReleaseCatalog.moduleRootOf(null)).isEmpty();
    }

    @Test
    public void absoluteDirectoryIsUsedAsIs() throws IOException {
        final Path releases = Files.createDirectories(this.dir.resolve("abs-releases"));
        Files.write(releases.resolve("kura-edge-3.0.0.jar"), "PK".getBytes(StandardCharsets.UTF_8));

        final Path resolved = ReleaseCatalog.resolveDirectory(releases.toString(), this.dir.resolve("whatever"), null);

        assertThat(resolved).isEqualTo(releases);
    }

    private ReleaseCatalog givenCatalog() {
        return new ReleaseCatalog(properties(this.dir));
    }

    private static ReleaseProperties properties(final Path dir) {
        final ReleaseProperties properties = new ReleaseProperties();
        properties.setDir(dir.toString());
        return properties;
    }

    private void writeJar(final String fileName, final String content) {
        writeJar(fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeJar(final String fileName, final byte[] content) {
        try {
            Files.write(this.dir.resolve(fileName), content);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
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
