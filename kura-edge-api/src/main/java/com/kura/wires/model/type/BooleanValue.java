/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 布尔类型取值，对照上游 {@code org.eclipse.kura.type.BooleanValue}。
 */
public final class BooleanValue extends TypedValue<Boolean> {

    /**
     * 创建布尔取值。
     *
     * @param value 布尔值
     */
    public BooleanValue(final boolean value) {
        super(value);
    }
}
