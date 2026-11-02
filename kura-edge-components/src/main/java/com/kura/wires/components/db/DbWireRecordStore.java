/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.db;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEmitter;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireReceiver;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.type.TypedValue;

/**
 * 数据库 Wire 记录存储组件：把接收到的 {@link WireRecord} 落库后继续向下游
 * 发射（保留上游 store 后 emit 的行为），对照上游
 * {@code org.eclipse.kura.internal.wire.db.store.DbWireRecordStore}。
 * <p>
 * 适配说明：上游通过 OSGi {@code BaseDbService} 直接拼 SQL 并动态建表；本项目统一
 * 使用 {@code wire_records} 单表 + JSON 单列，经构造注入的
 * {@link WireRecordRepository} 落库（工厂 {@link DbStoreFactory} 为 Spring Bean，把
 * repository 传给实例）。保留上游 {@code maximum.table.size} /
 * {@code cleanup.records.keep} 的自动清理语义：落库前若记录数达到上限，先删除
 * 最新记录之外的旧记录（keep = 0 时清空）。落库失败仅记录错误日志，
 * 不中断图运行（对照上游带重试的 store，这里交给调用方事务/重试）。
 * </p>
 */
public final class DbWireRecordStore implements WireEmitter, WireReceiver {

    /** 表最大记录数上限属性 key（与上游一致） */
    public static final String PROP_MAXIMUM_TABLE_SIZE = "maximum.table.size";

    /** 清理时保留记录数属性 key（与上游一致，0 表示清空） */
    public static final String PROP_CLEANUP_RECORDS_KEEP = "cleanup.records.keep";

    private static final int DEFAULT_MAXIMUM_TABLE_SIZE = 10000;
    private static final int DEFAULT_CLEANUP_RECORDS_KEEP = 5000;

    private static final Logger LOG = LoggerFactory.getLogger(DbWireRecordStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String pid;
    private final WireRecordRepository repository;
    private final WireEmitContext emitContext;

    /** 配置可经 updated() 热更新（对照上游 updated 回调），故为 volatile */
    private volatile Map<String, Object> properties;

    /**
     * 创建存储组件实例（由 {@link DbStoreFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param properties 组件配置属性（已合并默认值）
     * @param emitContext 引擎注入的发射上下文
     * @param repository 记录仓库（由 Spring Bean 工厂注入）
     */
    DbWireRecordStore(final String pid, final Map<String, Object> properties, final WireEmitContext emitContext,
            final WireRecordRepository repository) {
        this.pid = pid;
        this.properties = requireNonNull(properties, "Properties cannot be null");
        this.emitContext = emitContext;
        this.repository = requireNonNull(repository, "Wire Record Repository cannot be null");
    }

    /** {@inheritDoc} */
    @Override
    public String getPid() {
        return this.pid;
    }

    /**
     * 接收上游信封：逐条落库（含达限清理）后向下游转发
     * （对照上游 onWireReceive → store → emit）。
     *
     * @param wireEnvelope 收到的信封
     */
    @Override
    public void onWireReceive(final WireEnvelope wireEnvelope) {
        requireNonNull(wireEnvelope, "Wire Envelope cannot be null");
        try {
            cleanupIfNeeded();
            for (final WireRecord wireRecord : wireEnvelope.getRecords()) {
                store(wireEnvelope, wireRecord);
            }
        } catch (final Exception e) {
            LOG.error("Error while storing wire records", e);
        }
        // 落库后向下游转发记录（与落库是否成功无关，保留上游行为）
        this.emitContext.emit(new WireEnvelope(this.pid, wireEnvelope.getRecords()));
    }

    /**
     * 热更新组件配置（对照上游 OSGi updated 回调）。
     *
     * @param properties 新的配置属性
     */
    public void updated(final Map<String, Object> properties) {
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }

    private void cleanupIfNeeded() {
        final int maximumTableSize = getMaximumTableSize();
        if (this.repository.count() < maximumTableSize) {
            return;
        }
        final long newestId = this.repository.findFirstByOrderByIdDesc().map(WireRecordEntity::getId).orElse(0L);
        final long cutoff = newestId - getCleanupRecordsKeep();
        if (cutoff < 1) {
            return;
        }
        final long removed = this.repository.deleteByIdLessThanEqual(cutoff);
        LOG.info("Cleaned up {} wire record(s) exceeding table size {}", removed, maximumTableSize);
    }

    private void store(final WireEnvelope envelope, final WireRecord wireRecord) {
        final WireRecordEntity entity = new WireRecordEntity(envelope.getEmitterPid(),
                Instant.ofEpochMilli(envelope.getTimestamp()), toJson(wireRecord));
        this.repository.save(entity);
    }

    private String toJson(final WireRecord wireRecord) {
        final Map<String, Object> rawProperties = new HashMap<>();
        for (final Map.Entry<String, TypedValue<?>> entry : wireRecord.getProperties().entrySet()) {
            rawProperties.put(entry.getKey(), entry.getValue().getValue());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(rawProperties);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize wire record properties", e);
        }
    }

    private int getMaximumTableSize() {
        final Object size = this.properties.get(PROP_MAXIMUM_TABLE_SIZE);
        return size instanceof Integer ? (Integer) size : DEFAULT_MAXIMUM_TABLE_SIZE;
    }

    private int getCleanupRecordsKeep() {
        final Object keep = this.properties.get(PROP_CLEANUP_RECORDS_KEEP);
        return keep instanceof Integer ? (Integer) keep : DEFAULT_CLEANUP_RECORDS_KEEP;
    }
}
