/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测试专用 Boot 配置：仅供 {@code @DataJpaTest} 等切片测试发现配置用
 * （生产环境的唯一启动模块是 {@code kura-edge-rest}）。普通单元测试不会加载本类。
 */
@SpringBootApplication
public class ComponentsTestApplication {
}
