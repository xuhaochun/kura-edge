/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import com.kura.wires.model.type.DataType;

/**
 * 一次通道读写的返回记录（对照上游 {@code GwtChannelRecord} / {@code ChannelRecord} 的传输形态）。
 * <p>
 * 与上游 REST（{@code /services/assets}）的差异：上游 {@code _read} 只回「通道名 → 原始值」的映射、
 * 丢掉失败原因；而 GWT 版界面需要每行的状态与失败消息。本项目把状态带上（{@code status} /
 * {@code exceptionMessage}），{@code GET /_read} 仍保留上游的纯映射形态给脚本用。
 * </p>
 */
public class ChannelRecordDto {

    private String name;

    private DataType valueType;

    private Object value;

    private String status;

    private String exceptionMessage;

    public ChannelRecordDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建记录。
     *
     * @param name 通道名
     * @param valueType 取值类型
     * @param value 原始值（字节数组已 base64）
     * @param status {@code SUCCESS} / {@code FAILURE}
     * @param exceptionMessage 失败原因
     */
    public ChannelRecordDto(final String name, final DataType valueType, final Object value, final String status,
            final String exceptionMessage) {
        this.name = name;
        this.valueType = valueType;
        this.value = value;
        this.status = status;
        this.exceptionMessage = exceptionMessage;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public DataType getValueType() {
        return this.valueType;
    }

    public void setValueType(final DataType valueType) {
        this.valueType = valueType;
    }

    public Object getValue() {
        return this.value;
    }

    public void setValue(final Object value) {
        this.value = value;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getExceptionMessage() {
        return this.exceptionMessage;
    }

    public void setExceptionMessage(final String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }
}
