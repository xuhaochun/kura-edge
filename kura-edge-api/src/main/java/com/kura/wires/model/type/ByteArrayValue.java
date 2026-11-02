/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 字节数组类型取值，对照上游 {@code org.eclipse.kura.type.ByteArrayValue}。
 */
public final class ByteArrayValue extends TypedValue<byte[]> {

    /**
     * 创建字节数组取值。
     *
     * @param value 字节数组（内部拷贝，保持不可变语义）
     */
    public ByteArrayValue(final byte[] value) {
        super(value == null ? new byte[0] : value.clone());
    }

    @Override
    public byte[] getValue() {
        return super.getValue().clone();
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof ByteArrayValue && java.util.Arrays.equals(this.value, ((ByteArrayValue) obj).value);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(this.value);
    }
}
