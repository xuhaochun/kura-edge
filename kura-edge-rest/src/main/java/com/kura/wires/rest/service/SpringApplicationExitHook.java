/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 生产用退出钩子：先让 Spring 上下文优雅关闭（释放连接池、Flush H2），再结束 JVM。
 * <p>
 * 用 {@link SpringApplication#exit} 而不是直接 {@code System.exit}，是为了让
 * {@code GracefulShutdown}、{@code DisposableBean} 与 Flyway/Hikari 的收尾逻辑都跑到；
 * 直接 exit 会掐掉正在进行的 H2 写入。
 * </p>
 */
@Component
public class SpringApplicationExitHook implements BackendExitHook {

    private static final Logger log = LoggerFactory.getLogger(SpringApplicationExitHook.class);

    private final ApplicationContext context;

    /**
     * 创建退出钩子。
     *
     * @param context 应用上下文，用于优雅关闭
     */
    public SpringApplicationExitHook(final ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void exit() {
        log.info("热更新：开始优雅关闭后端，等待外壳换 jar 并重启");
        final int code = SpringApplication.exit(this.context, () -> 0);
        System.exit(code);
    }
}
