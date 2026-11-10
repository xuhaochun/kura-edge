/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.update;

/**
 * 更新清单（RFC 008/009）：设备定时拉取的 {@code feed.json}。
 * <p>
 * 放在 api 模块是因为这是**发布侧与设备侧的共享契约**：发布服务器（{@code kura-edge-release-server}）
 * 按它吐 JSON，设备（{@code kura-edge-rest} 的清单轮询）按它解析。两处各写一份字段名迟早会漂。
 * </p>
 * <p>
 * 只描述"当前应该跑哪个版本"，是一个单指针清单——设备只要比本机新就升级，不需要历史列表：
 * <pre>
 * {
 *   "protocol": 1,
 *   "latest": {
 *     "version": "1.0.4",
 *     "url": "kura-edge-1.0.4.jar",
 *     "sha256": "…64 位…",
 *     "size": 60126433,
 *     "publishedAt": "2026-11-10T09:30:00Z",
 *     "notes": "修复 Modbus 超时重连"
 *   }
 * }
 * </pre>
 * {@code url} 可以是相对路径（相对清单自身地址解析），这样整目录一起上传即可。
 * </p>
 */
public class FeedManifestDto {

    /** 清单协议版本；不认识的版本按"清单非法"处理，避免未来格式变更被老设备误读。 */
    private Integer protocol;

    /** 应执行的最新条目。 */
    private Latest latest;

    /** 供 Jackson 反序列化使用。 */
    public FeedManifestDto() {
    }

    /**
     * 返回清单协议版本。
     *
     * @return 协议版本
     */
    public Integer getProtocol() {
        return this.protocol;
    }

    /**
     * 设置清单协议版本。
     *
     * @param protocol 协议版本
     */
    public void setProtocol(final Integer protocol) {
        this.protocol = protocol;
    }

    /**
     * 返回最新条目。
     *
     * @return 最新条目，缺失时为 {@code null}
     */
    public Latest getLatest() {
        return this.latest;
    }

    /**
     * 设置最新条目。
     *
     * @param latest 最新条目
     */
    public void setLatest(final Latest latest) {
        this.latest = latest;
    }

    /**
     * 清单里的"应该跑哪个版本"。
     */
    public static class Latest {

        /** 版本号（会用作落地文件名，只允许字母数字与 . _ -）。 */
        private String version;

        /** jar 地址，绝对或相对清单地址。 */
        private String url;

        /** jar 的 sha256（64 位十六进制），必填。 */
        private String sha256;

        /** 字节数，可选（0 表示清单未给，由下载侧自行统计）。 */
        private long size;

        /** 发布时间（ISO-8601），仅作展示。 */
        private String publishedAt;

        /** 变更说明，仅作展示。 */
        private String notes;

        /** 供 Jackson 反序列化使用。 */
        public Latest() {
        }

        /**
         * 返回版本号。
         *
         * @return 版本号
         */
        public String getVersion() {
            return this.version;
        }

        /**
         * 设置版本号。
         *
         * @param version 版本号
         */
        public void setVersion(final String version) {
            this.version = version;
        }

        /**
         * 返回 jar 地址。
         *
         * @return 地址（可能是相对路径）
         */
        public String getUrl() {
            return this.url;
        }

        /**
         * 设置 jar 地址。
         *
         * @param url 地址
         */
        public void setUrl(final String url) {
            this.url = url;
        }

        /**
         * 返回 jar 的 sha256。
         *
         * @return 64 位十六进制
         */
        public String getSha256() {
            return this.sha256;
        }

        /**
         * 设置 jar 的 sha256。
         *
         * @param sha256 64 位十六进制
         */
        public void setSha256(final String sha256) {
            this.sha256 = sha256;
        }

        /**
         * 返回 jar 字节数。
         *
         * @return 字节数，0 表示清单未给出
         */
        public long getSize() {
            return this.size;
        }

        /**
         * 设置 jar 字节数。
         *
         * @param size 字节数
         */
        public void setSize(final long size) {
            this.size = size;
        }

        /**
         * 返回发布时间。
         *
         * @return ISO-8601 时间串
         */
        public String getPublishedAt() {
            return this.publishedAt;
        }

        /**
         * 设置发布时间。
         *
         * @param publishedAt ISO-8601 时间串
         */
        public void setPublishedAt(final String publishedAt) {
            this.publishedAt = publishedAt;
        }

        /**
         * 返回变更说明。
         *
         * @return 说明文本
         */
        public String getNotes() {
            return this.notes;
        }

        /**
         * 设置变更说明。
         *
         * @param notes 说明文本
         */
        public void setNotes(final String notes) {
            this.notes = notes;
        }
    }
}
