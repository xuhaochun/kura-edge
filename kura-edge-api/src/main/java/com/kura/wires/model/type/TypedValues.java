/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

import java.util.Base64;

/**
 * {@link TypedValue} 的工厂与字符串解析，对照上游 {@code org.eclipse.kura.type.TypedValues}。
 * <p>
 * 解析规则与上游逐条一致（错误的字面量也保持一致，因为前端与快照都按此文案回显）：
 * 布尔用宽松解析（非 {@code "true"} 一律 false）、字节数组按标准 base64 解码、
 * 数值型用对应的 {@code parseXxx} 方法。
 * </p>
 */
public final class TypedValues {

    private TypedValues() {
        // 工具类
    }

    /**
     * 按类型把字符串解析为类型化取值（对照上游 {@code TypedValues.parseTypedValue}）。
     *
     * @param type 目标类型
     * @param value 字符串形式的取值
     * @return 类型化取值
     * @throws IllegalArgumentException type 或 value 为空，或 value 无法转换为该类型时
     */
    public static TypedValue<?> parseTypedValue(final DataType type, final String value) {
        if (type == null) {
            throw new IllegalArgumentException("Cannot convert to TypedValue: type cannot be null");
        }
        try {
            switch (type) {
                case BOOLEAN:
                    return new BooleanValue(Boolean.parseBoolean(value));
                case BYTE_ARRAY:
                    return new ByteArrayValue(Base64.getDecoder().decode(value));
                case DOUBLE:
                    return new DoubleValue(Double.parseDouble(value));
                case INTEGER:
                    return new IntegerValue(Integer.parseInt(value));
                case LONG:
                    return new LongValue(Long.parseLong(value));
                case FLOAT:
                    return new FloatValue(Float.parseFloat(value));
                case STRING:
                    return new StringValue(value);
                default:
                    throw new IllegalArgumentException("Cannot convert to TypedValue");
            }
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException(value + " cannot be converted into a TypedValue of type " + type, e);
        }
    }

    /**
     * 把类型化取值转换为字符串（字节数组为 base64），供 CSV / 文本回写使用。
     *
     * @param value 类型化取值
     * @return 字符串形式
     */
    public static String toStringValue(final TypedValue<?> value) {
        if (value == null) {
            return null;
        }
        if (value.getValue() instanceof final byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value.getValue());
    }

    /**
     * 创建布尔取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<Boolean> newBooleanValue(final boolean value) {
        return new BooleanValue(value);
    }

    /**
     * 创建字节数组取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<byte[]> newByteArrayValue(final byte[] value) {
        return new ByteArrayValue(value);
    }

    /**
     * 创建双精度取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<Double> newDoubleValue(final double value) {
        return new DoubleValue(value);
    }

    /**
     * 创建整型取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<Integer> newIntegerValue(final int value) {
        return new IntegerValue(value);
    }

    /**
     * 创建长整型取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<Long> newLongValue(final long value) {
        return new LongValue(value);
    }

    /**
     * 创建单精度取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<Float> newFloatValue(final float value) {
        return new FloatValue(value);
    }

    /**
     * 创建字符串取值。
     *
     * @param value 取值
     * @return 类型化取值
     */
    public static TypedValue<String> newStringValue(final String value) {
        return new StringValue(value);
    }
}
