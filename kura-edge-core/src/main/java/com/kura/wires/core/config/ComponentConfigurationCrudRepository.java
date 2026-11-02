/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.config;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 组件配置实体的 CRUD 仓库（仅 Service 层可触碰，Rules 04）。
 */
public interface ComponentConfigurationCrudRepository extends JpaRepository<ComponentConfigurationEntity, String> {
    // 全部使用 JpaRepository 默认方法
}
