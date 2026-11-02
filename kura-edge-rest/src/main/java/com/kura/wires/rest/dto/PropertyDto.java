/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 配置属性 DTO：{@code type} 为上游 {@code Scalar} 枚举名（如 STRING / PASSWORD），
 * {@code value} 为原始取值，对照上游 {@code PropertyDTO}。
 * <p>
 * 密码属性在 GET 类输出中被脱敏为 {@code PASSWORD} 占位符。
 * </p>
 */
public class PropertyDto {

    private String type;
    private Object value;

    public PropertyDto() {
        // Jackson 反序列化用
    }

    public PropertyDto(final String type, final Object value) {
        this.type = type;
        this.value = value;
    }

    public String getType() {
        return this.type;
    }

    public Object getValue() {
        return this.value;
    }
}
