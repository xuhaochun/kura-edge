/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.kura.wires.model.type.TypedValue;

/**
 * Wire 消息记录，代表组件间传递的一条 key-value 数据，对照上游
 * {@code org.eclipse.kura.wire.WireRecord}。
 * <p>
 * 本对象<b>不可变</b>：属性值受 {@link TypedValue} 约束，组件不得修改传入的
 * Record，需要变更时必须创建新实例。
 * </p>
 */
public final class WireRecord {

    private final Map<String, TypedValue<?>> properties;

    /**
     * 创建 WireRecord。
     *
     * @param properties key-value 属性
     * @throws NullPointerException properties 为 null 时
     */
    public WireRecord(final Map<String, TypedValue<?>> properties) {
        requireNonNull(properties, "Properties cannot be null");
        this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
    }

    /**
     * 返回本记录包含的属性（只读）。
     *
     * @return 只读属性表
     */
    public Map<String, TypedValue<?>> getProperties() {
        return this.properties;
    }
}
