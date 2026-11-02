/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 整图快照实体的 CRUD 仓库（仅 SnapshotStore 实现可触碰，Rules 04）。
 */
public interface WireGraphSnapshotRepository extends JpaRepository<WireGraphSnapshotEntity, String> {
    // 全部使用 JpaRepository 默认方法
}
