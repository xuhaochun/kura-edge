/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 通道取值的类型枚举，对照上游 {@code org.eclipse.kura.type.DataType}。
 * <p>
 * <b>声明顺序必须与上游一致</b>（BOOLEAN、BYTE_ARRAY、DOUBLE、INTEGER、LONG、FLOAT、STRING）：
 * 上游 metatype 下拉选项按枚举顺序生成，顺序变了会导致配置界面选项次序与 Kura 原生快照不一致。
 * </p>
 */
public enum DataType {

    /** 布尔 */
    BOOLEAN,
    /** 字节数组（REST/JSON 传输时 base64） */
    BYTE_ARRAY,
    /** 双精度浮点 */
    DOUBLE,
    /** 整型 */
    INTEGER,
    /** 长整型 */
    LONG,
    /** 单精度浮点 */
    FLOAT,
    /** 字符串 */
    STRING;

    /**
     * 大小写不敏感地解析取值类型（对照上游 {@code DataType.getDataType}）。
     *
     * @param dataTypeString 字符串形式
     * @return 取值类型
     * @throws IllegalArgumentException 无法识别时
     */
    public static DataType getDataType(final String dataTypeString) {
        if (dataTypeString == null) {
            throw new IllegalArgumentException("Cannot convert to DataType");
        }
        for (final DataType value : values()) {
            if (value.name().equalsIgnoreCase(dataTypeString)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Cannot convert to DataType");
    }
}
