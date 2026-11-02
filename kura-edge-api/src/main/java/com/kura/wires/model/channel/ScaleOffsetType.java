/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

/**
 * 通道 scale/offset 的运算类型，对照上游 {@code org.eclipse.kura.channel.ScaleOffsetType}。
 * <p>
 * {@code DEFINED_BY_VALUE_TYPE} 表示「按通道的 {@code +value.type} 运算」；其余四种表示
 * 「按指定数值类型运算，再把结果转换回通道 {@code +value.type}」。
 * </p>
 */
public enum ScaleOffsetType {

    /** 由通道取值类型决定 */
    DEFINED_BY_VALUE_TYPE,
    /** 单精度 */
    FLOAT,
    /** 双精度 */
    DOUBLE,
    /** 整型 */
    INTEGER,
    /** 长整型 */
    LONG;

    /**
     * 大小写不敏感地解析 scale/offset 类型（对照上游 {@code getScaleOffsetType}）。
     *
     * @param stringScaleOffsetType 字符串形式
     * @return scale/offset 类型
     * @throws IllegalArgumentException 无法识别时
     */
    public static ScaleOffsetType getScaleOffsetType(final String stringScaleOffsetType) {
        if (stringScaleOffsetType == null) {
            throw new IllegalArgumentException("Cannot convert to ScaleOffsetType");
        }
        for (final ScaleOffsetType value : values()) {
            if (value.name().equalsIgnoreCase(stringScaleOffsetType)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Cannot convert to ScaleOffsetType");
    }

    /**
     * 该类型对应的取值类型（仅数值型可用）。
     *
     * @return 取值类型
     * @throws IllegalArgumentException 类型为 {@code DEFINED_BY_VALUE_TYPE} 时
     */
    public com.kura.wires.model.type.DataType toDataType() {
        switch (this) {
            case FLOAT:
                return com.kura.wires.model.type.DataType.FLOAT;
            case DOUBLE:
                return com.kura.wires.model.type.DataType.DOUBLE;
            case INTEGER:
                return com.kura.wires.model.type.DataType.INTEGER;
            case LONG:
                return com.kura.wires.model.type.DataType.LONG;
            default:
                throw new IllegalArgumentException("Unsupported scale offset type: " + this);
        }
    }
}
