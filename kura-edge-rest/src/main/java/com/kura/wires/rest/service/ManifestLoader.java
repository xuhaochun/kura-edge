/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

/**
 * 更新清单读取器（RFC 008）：把 {@code feed.json} 的原始文本拉回来。
 * <p>
 * 与 {@link ArtifactFetcher} 分开是因为两者关切不同：清单是几 KB 的 JSON，必须严格限长、
 * 严格超时、**不许重试到把服务器压垮**；jar 是几十 MB 的二进制，要边下边算摘要。
 * 抽成接口同样是为了让 {@link UpdateFeedService} 的判定逻辑能在不联网的单测里跑全。
 */
public interface ManifestLoader {

    /**
     * 拉取清单原文。
     *
     * @param url 清单绝对地址（只接受 http/https）
     * @return 清单 JSON 文本
     * @throws com.kura.wires.exception.WiresException 地址非法、拉取失败或超过大小上限
     */
    String load(String url);
}
