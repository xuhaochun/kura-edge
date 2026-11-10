/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.nio.file.Path;

/**
 * 更新包下载器（RFC 006 §2.4）：把远端 jar 拉到本地暂存文件，并给出内容摘要。
 * <p>
 * 抽成接口是为了让 {@link BackendUpdateService} 的状态机（串行化、摘要比对、落 {@code pending.json}）
 * 能在不联网的单测里跑全；生产实现是 {@link HttpArtifactFetcher}。
 * </p>
 */
public interface ArtifactFetcher {

    /**
     * 下载到指定暂存文件。
     * <p>
     * 约定：只写 {@code partFile}（调用方负责命名成 {@code *.part}），下载或大小校验失败时由实现方
     * 删除该文件，成功时文件已 fsync 落盘、可直接 rename 就位。
     * </p>
     *
     * @param url 下载地址（实现只接受 http/https）
     * @param partFile 暂存文件路径（父目录不存在时自动创建）
     * @param maxBytes 允许的最大字节数，超出即中止
     * @return 字节数与 sha256
     * @throws com.kura.wires.exception.WiresException 地址非法（{@code UPDATE_REQUEST_INVALID}）
     *         或下载失败（{@code UPDATE_DOWNLOAD_FAILED}）
     */
    FetchedArtifact fetch(String url, Path partFile, long maxBytes);
}
