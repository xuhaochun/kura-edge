/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.db;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 落库的 Wire 记录实体，表名 {@code wire_records}，对照上游
 * {@code org.eclipse.kura.internal.wire.db.store.DbWireRecordStore} 的建表语义。
 * <p>
 * 适配说明：上游把一条 {@code WireRecord} 的每个属性拆成一行（列名动态建表）；
 * 本项目简化为一条记录一行，属性表用 Jackson 序列化为 JSON 单列
 * （{@code prop_value}，存原始值），避免动态建表与列修复（上游 reconcileDB）。
 * </p>
 */
@Entity
@Table(name = "wire_records")
public class WireRecordEntity {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发射该记录的组件 PID */
    @Column(name = "emitter_pid", nullable = false)
    private String emitterPid;

    /** 接收时刻（取信封发射时间戳） */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** 记录属性表的 JSON 序列化结果（key → 原始值） */
    @Lob
    @Column(name = "prop_value", nullable = false)
    private String propValue;

    /** 创建空实体（供 JPA 反射使用）。 */
    protected WireRecordEntity() {
        // JPA 反射用
    }

    /**
     * 创建记录实体。
     *
     * @param emitterPid 发射组件 PID
     * @param receivedAt 接收时刻
     * @param propValue 属性表 JSON
     */
    public WireRecordEntity(final String emitterPid, final Instant receivedAt, final String propValue) {
        this.emitterPid = emitterPid;
        this.receivedAt = receivedAt;
        this.propValue = propValue;
    }

    /**
     * 返回自增主键（持久化后由数据库生成）。
     *
     * @return 主键
     */
    public Long getId() {
        return this.id;
    }

    /**
     * 返回发射组件 PID。
     *
     * @return 发射组件 PID
     */
    public String getEmitterPid() {
        return this.emitterPid;
    }

    /**
     * 返回接收时刻。
     *
     * @return 接收时刻
     */
    public Instant getReceivedAt() {
        return this.receivedAt;
    }

    /**
     * 返回属性表 JSON。
     *
     * @return 属性表 JSON
     */
    public String getPropValue() {
        return this.propValue;
    }
}
