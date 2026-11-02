/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 双精度浮点类型取值，对照上游 {@code org.eclipse.kura.type.DoubleValue}。
 */
public final class DoubleValue extends TypedValue<Double> {

    /**
     * 创建双精度取值。
     *
     * @param value 双精度值
     */
    public DoubleValue(final double value) {
        super(value);
    }
}
