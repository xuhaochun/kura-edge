/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kura-edge Spring Boot 启动类：唯一启动模块，聚合 api / core / components
 * （对照上游 OSGi 容器内多个 bundle 的组合方式）。
 * <p>
 * core 的 JPA 实体与仓库分布在 {@code com.kura.wires.core} 的 config / snapshot
 * 包内，由 {@link com.kura.wires.rest.config.JpaConfig} 显式启用。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.kura.wires")
public class KuraEdgeApplication {

    /**
     * 应用入口。
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        SpringApplication.run(KuraEdgeApplication.class, args);
    }
}
