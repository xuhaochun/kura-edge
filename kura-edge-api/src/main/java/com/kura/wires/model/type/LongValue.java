/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 长整数类型取值，对照上游 {@code org.eclipse.kura.type.LongValue}。
 */
public final class LongValue extends TypedValue<Long> {

    /**
     * 创建长整数取值。
     *
     * @param value 长整数值
     */
    public LongValue(final long value) {
        super(value);
    }
}
