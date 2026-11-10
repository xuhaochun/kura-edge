/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

/**
 * 一次下载的产物摘要：落盘后的字节数与内容 sha256（64 位小写十六进制）。
 * <p>
 * 由 {@link ArtifactFetcher} 边下边算得出，供上层与请求里声明的摘要比对；不含任何路径信息，
 * 文件位置由调用方掌握。
 * </p>
 */
public class FetchedArtifact {

    private final long size;

    private final String sha256;

    /**
     * 创建下载摘要。
     *
     * @param size 落盘字节数
     * @param sha256 内容 sha256（64 位小写十六进制）
     */
    public FetchedArtifact(final long size, final String sha256) {
        this.size = size;
        this.sha256 = sha256;
    }

    /**
     * 返回落盘字节数。
     *
     * @return 字节数
     */
    public long getSize() {
        return this.size;
    }

    /**
     * 返回内容 sha256。
     *
     * @return 64 位小写十六进制摘要
     */
    public String getSha256() {
        return this.sha256;
    }
}
