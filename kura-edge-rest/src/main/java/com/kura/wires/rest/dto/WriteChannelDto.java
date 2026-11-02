/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import com.kura.wires.model.type.DataType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个通道的写请求。{@code value} 允许是 JSON 数字/布尔/字符串（上游同样接受），
 * 服务端按 {@code type} 统一解析。
 */
public class WriteChannelDto {

    @NotBlank
    private String name;

    @NotNull
    private DataType type;

    private Object value;

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public DataType getType() {
        return this.type;
    }

    public void setType(final DataType type) {
        this.type = type;
    }

    public Object getValue() {
        return this.value;
    }

    public void setValue(final Object value) {
        this.value = value;
    }
}
