/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 整数类型取值，对照上游 {@code org.eclipse.kura.type.IntegerValue}。
 */
public final class IntegerValue extends TypedValue<Integer> {

    /**
     * 创建整数取值。
     *
     * @param value 整数值
     */
    public IntegerValue(final int value) {
        super(value);
    }
}
