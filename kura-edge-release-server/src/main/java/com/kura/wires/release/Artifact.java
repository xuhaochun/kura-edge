/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import java.nio.file.Path;

/**
 * 发布目录里的一个后端 jar。
 *
 * @param version 版本号（从文件名解析，已校验字符白名单）
 * @param fileName 文件名（{@code kura-edge-<版本>.jar}）
 * @param size 字节数
 * @param sha256 小写十六进制摘要
 * @param modifiedAt 落盘时间（ISO-8601）
 * @param path 绝对路径
 */
public record Artifact(String version, String fileName, long size, String sha256, String modifiedAt, Path path) {
}
