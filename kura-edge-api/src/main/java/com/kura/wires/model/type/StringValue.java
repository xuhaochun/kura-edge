/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 字符串类型取值，对照上游 {@code org.eclipse.kura.type.StringValue}。
 */
public final class StringValue extends TypedValue<String> {

    /**
     * 创建字符串取值。
     *
     * @param value 字符串值
     */
    public StringValue(final String value) {
        super(value);
    }
}
