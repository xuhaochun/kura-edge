/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 字符类型取值，对照上游 {@code org.eclipse.kura.type.CharValue}。
 */
public final class CharValue extends TypedValue<Character> {

    /**
     * 创建字符取值。
     *
     * @param value 字符值
     */
    public CharValue(final char value) {
        super(value);
    }
}
