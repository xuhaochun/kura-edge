/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 装配：core 的实体与仓库分布在 {@code com.kura.wires.core} 的 config / snapshot
 * 包内、components 的 DAO 在 {@code com.kura.wires.components.db}，两者都需显式扩大扫描范围
 * （否则 DbStoreFactory 找不到 WireRecordRepository，整个上下文无法启动）。
 * 独立成配置类以便 MVC 切片测试（@WebMvcTest）不加载 JPA。
 */
@Configuration
@EntityScan(basePackages = { "com.kura.wires.core", "com.kura.wires.components" })
@EnableJpaRepositories(basePackages = { "com.kura.wires.core", "com.kura.wires.components" })
public class JpaConfig {
}
