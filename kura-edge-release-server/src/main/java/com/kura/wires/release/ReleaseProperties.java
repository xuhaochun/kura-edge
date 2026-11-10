/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 发布服务器配置。
 */
@ConfigurationProperties(prefix = "kura.release")
public class ReleaseProperties {

    /**
     * jar 所在目录，默认 {@code releases}（模块根下的发布目录）。
     * <p>
     * 绝对路径原样使用；相对路径先按进程工作目录找，该目录不存在时回退到代码所在模块根
     * （{@code target/classes} 或 {@code target/*.jar} 的上两级）——这样 {@code java -jar target/x.jar}
     * 与在 IDEA 里直接 Run（工作目录是仓库根）都能扫到 {@code kura-edge-release-server/releases/}。
     * </p>
     */
    private String dir = "releases";

    /** 对外可达的基地址（可选，只用于首页示例文案），例如 {@code http://192.168.31.40:8081}。 */
    private String baseUrl = "";

    /**
     * 返回 jar 目录。
     *
     * @return 目录路径
     */
    public String getDir() {
        return this.dir;
    }

    /**
     * 设置 jar 目录。
     *
     * @param dir 目录路径
     */
    public void setDir(final String dir) {
        this.dir = dir;
    }

    /**
     * 返回对外基地址。
     *
     * @return 基地址，未配置时为空串
     */
    public String getBaseUrl() {
        return this.baseUrl;
    }

    /**
     * 设置对外基地址。
     *
     * @param baseUrl 基地址
     */
    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
