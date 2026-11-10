/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.release;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 更新发布服务器入口（RFC 009）：在 8081 上把 {@code releases/} 目录里的后端 jar 暴露给现场设备。
 * <p>
 * 为什么单独起一个服务而不是用现成的 nginx/文件服务器：
 * </p>
 * <ul>
 *   <li>设备要的不是"一个能下的链接"，而是 {@code feed.json} 清单——版本号、sha256、大小都得对，
 *       并且要能自动指向"目录里最新的那个 jar"。手写清单最容易出的错就是发了新 jar 忘了改清单；</li>
 *   <li>现场往往只有 Windows 机器和 JDK，装 nginx 反而更难；这个服务就是个 {@code java -jar}；</li>
 *   <li>它必须**只**做发布，不碰数据库、不碰图：故障域和业务服务彻底分开。</li>
 * </ul>
 * 目录里的 jar 由构建脚本直接拷进来（{@code build-desktop.sh} 发布步骤），也可以人工丢进去。
 */
@SpringBootApplication
@EnableConfigurationProperties(ReleaseProperties.class)
public class ReleaseServerApplication {

    /**
     * 应用入口。
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        SpringApplication.run(ReleaseServerApplication.class, args);
    }
}
