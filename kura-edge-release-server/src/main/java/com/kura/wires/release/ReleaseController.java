/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布服务器端点（RFC 009）。
 * <p>
 * 只有四类路径，全部只读：
 * </p>
 * <ul>
 *   <li>{@code GET /feed.json} —— 设备用的更新清单（版本 / 相对地址 / sha256 / 大小）；</li>
 *   <li>{@code GET /kura-edge-<版本>.jar} —— jar 本体；</li>
 *   <li>{@code GET /} —— 人看的首页：目录里有哪些版本、设备该怎么配；</li>
 *   <li>{@code GET /healthz} —— 探活（脚本/监控用）。</li>
 * </ul>
 * 没有上传接口：jar 由构建脚本或人工放进目录。少一个写入口就少一类被现场乱传东西的风险。
 */
@RestController
public class ReleaseController {

    /** jar 的 Content-Type；设备不校验它，但下载器（浏览器/curl）看到会存成 .jar。 */
    private static final MediaType JAR = MediaType.parseMediaType("application/java-archive");

    private final ReleaseCatalog catalog;

    private final ReleaseProperties properties;

    /**
     * 创建控制器。
     *
     * @param catalog 发布目录索引
     * @param properties 发布配置
     */
    public ReleaseController(final ReleaseCatalog catalog, final ReleaseProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    /**
     * 更新清单。
     *
     * @return 清单 JSON；目录里没有 jar 时 404
     */
    @GetMapping(value = "/feed.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> feed() {
        return this.catalog.feed()
                .<ResponseEntity<Object>>map(manifest -> ResponseEntity.ok()
                        // 清单随时会变（放新 jar），不允许中间缓存：设备拿旧清单就是"永远不升级"
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(manifest))
                .orElseGet(() -> ResponseEntity.status(404)
                        // 把实际扫描到的绝对路径写出来：现场最常见的排障问题就是"jar 放错目录"
                        .body(Map.of("code", 404001,
                                "message", "发布目录 " + this.catalog.directory() + " 里还没有 kura-edge-<版本>.jar")));
    }

    /**
     * 下载 jar。
     *
     * @param fileName 文件名（必须与索引里的某个发布物完全一致）
     * @return jar 内容；不认识的文件名 404
     */
    @GetMapping("/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable final String fileName) {
        return this.catalog.find(fileName)
                .map(artifact -> {
                    final Path path = artifact.path();
                    return ResponseEntity.ok()
                            .contentType(JAR)
                            .contentLength(artifact.size())
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + artifact.fileName() + "\"")
                            .body((Resource) new FileSystemResource(path));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 探活。
     *
     * @return 固定文本 {@code ok}
     */
    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }

    /**
     * 首页：当前有哪些版本 + 设备该怎么配。
     *
     * @return HTML
     */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        final List<Artifact> artifacts = this.catalog.list();
        final StringBuilder html = new StringBuilder(2048);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>kura-edge 发布服务器</title><style>")
                .append("body{font-family:system-ui,sans-serif;margin:32px;color:#303133;line-height:1.6}")
                .append("table{border-collapse:collapse;margin:12px 0}th,td{border:1px solid #dcdfe6;")
                .append("padding:6px 12px;text-align:left;font-size:14px}code{background:#f5f7fa;")
                .append("padding:2px 4px;border-radius:3px}pre{background:#f5f7fa;padding:12px;")
                .append("border-radius:4px;overflow-x:auto}</style></head><body>")
                .append("<h2>kura-edge 发布服务器</h2>")
                .append("<p>发布目录：<code>").append(escape(this.catalog.directory().toString()))
                .append("</code></p>");
        if (artifacts.isEmpty()) {
            html.append("<p>目录里还没有 <code>kura-edge-&lt;版本&gt;.jar</code>。")
                    .append("跑一次 <code>bash desktop/scripts/build-desktop.sh --jar-only</code> ")
                    .append("或直接把 jar 拷进来即可。</p>");
        } else {
            html.append("<table><tr><th>版本</th><th>文件</th><th>大小</th><th>sha256</th><th>落盘时间</th></tr>");
            for (final Artifact artifact : artifacts) {
                html.append("<tr><td>").append(escape(artifact.version()))
                        .append("</td><td><a href=\"/").append(escape(artifact.fileName())).append("\">")
                        .append(escape(artifact.fileName())).append("</a></td><td>")
                        .append(humanSize(artifact.size())).append("</td><td><code>")
                        .append(artifact.sha256()).append("</code></td><td>")
                        .append(escape(artifact.modifiedAt())).append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append("<h3>设备怎么配</h3>")
                .append("<p>清单地址：<code>").append(feedUrl()).append("</code></p>")
                .append("<p>在设备上设环境变量（系统环境变量，或外壳启动环境）：</p>")
                .append("<pre>KURA_EDGE_UPDATE_FEED=").append(feedUrl()).append("</pre>")
                .append("<p>之后设备每 5 分钟拉一次清单，发现更新的版本就自己蓝绿升级。")
                .append("临时催一台：</p>")
                .append("<pre>curl -u admin:&lt;口令&gt; -X POST http://&lt;设备&gt;:&lt;端口&gt;")
                .append("/api/v1/system/feed/check</pre></body></html>");
        return html.toString();
    }

    /**
     * 首页里展示的清单地址：优先用配置的对外基地址，没配就留个占位符让运维自己填。
     */
    private String feedUrl() {
        final String configured = this.properties.getBaseUrl();
        if (configured == null || configured.isBlank()) {
            return "&lt;本机IP&gt;:8081/feed.json";
        }
        final String trimmed = configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1)
                : configured;
        return escape(trimmed) + "/feed.json";
    }

    private static String humanSize(final long bytes) {
        return bytes >= 1024 * 1024 ? String.format("%.1f MiB", bytes / 1024.0 / 1024.0) : bytes + " B";
    }

    private static String escape(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
