/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 组件间传递的数据信封：发射时间戳 + 若干 {@link WireRecord}，对照上游
 * {@code org.eclipse.kura.wire.WireEnvelope}。不可变对象。
 */
public final class WireEnvelope {

    private static final String SCOPE = "WIRES";

    private final long timestamp;
    private final String emitterPid;
    private final String scope;
    private final List<WireRecord> records;

    /**
     * 以当前时间创建信封（对照上游构造器语义）。
     *
     * @param emitterPid 发射组件 PID
     * @param records 携带的记录列表
     */
    public WireEnvelope(final String emitterPid, final List<WireRecord> records) {
        this(System.currentTimeMillis(), emitterPid, SCOPE, records);
    }

    /**
     * 以指定时间创建信封（供引擎与测试使用）。
     *
     * @param timestamp 发射时间戳（epoch millis）
     * @param emitterPid 发射组件 PID
     * @param scope 作用域，Kura Wires 固定为 {@code WIRES}
     * @param records 携带的记录列表
     */
    public WireEnvelope(final long timestamp, final String emitterPid, final String scope,
            final List<WireRecord> records) {
        requireNonNull(emitterPid, "Emitter PID cannot be null");
        requireNonNull(records, "Records cannot be null");
        this.timestamp = timestamp;
        this.emitterPid = emitterPid;
        this.scope = scope == null ? SCOPE : scope;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    /**
     * 返回发射时间戳（epoch millis）。
     *
     * @return 时间戳
     */
    public long getTimestamp() {
        return this.timestamp;
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
     * 返回作用域（固定 {@code WIRES}，对照上游 BasicEnvelope.scope）。
     *
     * @return 作用域
     */
    public String getScope() {
        return this.scope;
    }

    /**
     * 返回携带的记录列表（只读）。
     *
     * @return 只读记录列表
     */
    public List<WireRecord> getRecords() {
        return this.records;
    }
}
