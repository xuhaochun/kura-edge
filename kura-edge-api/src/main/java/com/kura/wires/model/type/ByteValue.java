/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 字节类型取值，对照上游 {@code org.eclipse.kura.type.ByteValue}。
 */
public final class ByteValue extends TypedValue<Byte> {

    /**
     * 创建字节取值。
     *
     * @param value 字节值
     */
    public ByteValue(final byte value) {
        super(value);
    }
}
