/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.db;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wire 记录落库仓库，对照上游 {@code DbServiceProvider} 的 insert/truncate 能力。
 * <p>
 * JPA 自动配置由唯一的 Boot 启动模块 {@code kura-edge-rest}（包根
 * {@code com.kura.wires}）扫描到本接口。
 * </p>
 */
public interface WireRecordRepository extends JpaRepository<WireRecordEntity, Long> {

    /**
     * 返回主键最大的一条记录（用于计算清理截止主键）。
     *
     * @return 最新记录
     */
    Optional<WireRecordEntity> findFirstByOrderByIdDesc();

    /**
     * 删除主键小于等于截止值的记录，返回删除条数（对照上游
     * {@code DbServiceProvider.truncate}：超出 {@code cleanup.records.keep} 的旧记录被清理）。
     *
     * @param cutoff 截止主键（含）
     * @return 删除的记录条数
     */
    @Transactional
    long deleteByIdLessThanEqual(long cutoff);
}
