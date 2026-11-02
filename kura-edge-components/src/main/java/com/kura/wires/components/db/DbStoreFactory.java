/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.db;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;

/**
 * DbWireRecordStore 组件工厂：向引擎注册存储组件的定义与创建逻辑，对照上游
 * OSGi DS + metatype（{@code OSGI-INF/metatype/org.eclipse.kura.wire.DbWireRecordStore.xml}）。
 * <p>
 * 工厂无法自行从 Spring 上下文取 repository，故实现为 Spring
 * {@code @Component} 并构造注入 {@link WireRecordRepository}，create() 时把 repository
 * 传给 {@link DbWireRecordStore} 实例。默认端口数与上游组件语义一致：1 入 / 1 出。
 * 上游的 {@code table.name}（动态建表）与 {@code BaseDbService.target}（OSGi
 * 目标过滤器）不再保留：本项目统一使用 {@code wire_records} 单表与 Spring 注入。
 * </p>
 */
@Component
public class DbStoreFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.DbWireRecordStore";

    private final WireRecordRepository repository;

    /**
     * 创建工厂（Spring 注入记录仓库）。
     *
     * @param repository 记录仓库
     */
    public DbStoreFactory(final WireRecordRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String factoryPid() {
        return FACTORY_PID;
    }

    /** {@inheritDoc} */
    @Override
    public int defaultInputPortCount() {
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public int defaultOutputPortCount() {
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public Ocd getDefinition() {
        final Ad maximumTableSize = new Ad(DbWireRecordStore.PROP_MAXIMUM_TABLE_SIZE, "maximum.table.size",
                Scalar.INTEGER, true, 10000);
        maximumTableSize.setDescription("Maximum table size");
        maximumTableSize.setMin(1.0);

        final Ad cleanupRecordsKeep = new Ad(DbWireRecordStore.PROP_CLEANUP_RECORDS_KEEP, "cleanup.records.keep",
                Scalar.INTEGER, true, 5000);
        cleanupRecordsKeep.setDescription(
                "Specifies the number of records to keep while performing a cleanup operation "
                        + "(if set to 0 all the records will be deleted).");
        cleanupRecordsKeep.setMin(0.0);

        return new Ocd(FACTORY_PID, "DB Store",
                "A wire component that provides storage for Wire Records produced by connected WireEmitters.",
                List.of(maximumTableSize, cleanupRecordsKeep));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        assertAtLeast(properties, DbWireRecordStore.PROP_MAXIMUM_TABLE_SIZE, 1);
        assertAtLeast(properties, DbWireRecordStore.PROP_CLEANUP_RECORDS_KEEP, 0);
        return new DbWireRecordStore(pid, properties, emitContext, this.repository);
    }

    private static void assertAtLeast(final Map<String, Object> properties, final String key, final int min) {
        final Object value = properties.get(key);
        if (value instanceof Integer && (Integer) value < min) {
            throw new IllegalArgumentException(key + " must be at least " + min);
        }
    }
}
