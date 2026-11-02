/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * core 模块切片测试用的最小 Spring Boot 配置：仅供 {@code @DataJpaTest}
 * 发现自动配置与实体/仓库，不注册业务 Bean。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class CoreJpaTestApplication {
}
