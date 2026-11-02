/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;

/**
 * 一次通道读写的记录，对照上游 {@code org.eclipse.kura.channel.ChannelRecord}。
 * <p>
 * 与上游的差异：上游 {@code createWriteRecord(name, value)} 用 {@code value.getType()} 反推取值类型，
 * 本项目显式传入 {@code valueType}——本项目 {@code TypedValue} 没有 {@code getType()}（还多出
 * {@code ByteValue}/{@code CharValue} 两个上游没有的子类，无法反推）。语义等价，因为调用方总是
 * 知道目标通道的 {@code +value.type}。
 * </p>
 * <p>
 * 记录是<b>可变</b>的：驱动就地填入 value/status，资产层再回填错误——与上游一致。
 * </p>
 */
public final class ChannelRecord {

    private Map<String, Object> channelConfig;

    private ChannelStatus channelStatus;

    private String name;

    private DataType valueType;

    private TypedValue<?> value;

    private long timestamp;

    private String unit;

    private ChannelRecord() {
        // 工厂方法构造
    }

    /**
     * 创建读请求记录。
     *
     * @param channelName 通道名
     * @param valueType 期望读出的类型
     * @return 记录
     */
    public static ChannelRecord createReadRecord(final String channelName, final DataType valueType) {
        return createReadRecord(channelName, valueType, "");
    }

    /**
     * 创建带单位的读请求记录。
     *
     * @param channelName 通道名
     * @param valueType 期望读出的类型
     * @param unit 单位
     * @return 记录
     */
    public static ChannelRecord createReadRecord(final String channelName, final DataType valueType,
            final String unit) {
        requireNonNull(channelName, "Channel Name cannot be null");
        requireNonNull(valueType, "Value Type cannot be null");
        final ChannelRecord result = new ChannelRecord();
        result.name = channelName;
        result.valueType = valueType;
        result.unit = unit;
        return result;
    }

    /**
     * 创建写请求记录。
     *
     * @param channelName 通道名
     * @param valueType 写入值类型
     * @param value 写入值
     * @return 记录
     */
    public static ChannelRecord createWriteRecord(final String channelName, final DataType valueType,
            final TypedValue<?> value) {
        requireNonNull(channelName, "Channel Name cannot be null");
        requireNonNull(valueType, "Value Type cannot be null");
        requireNonNull(value, "Value cannot be null");
        final ChannelRecord result = new ChannelRecord();
        result.name = channelName;
        result.valueType = valueType;
        result.value = value;
        return result;
    }

    /**
     * 创建状态记录（读操作的失败结果用）。
     *
     * @param channelName 通道名
     * @param status 状态
     * @return 记录
     */
    public static ChannelRecord createStatusRecord(final String channelName, final ChannelStatus status) {
        requireNonNull(channelName, "Channel Name cannot be null");
        requireNonNull(status, "Status cannot be null");
        final ChannelRecord result = new ChannelRecord();
        result.name = channelName;
        result.channelStatus = status;
        return result;
    }

    public Map<String, Object> getChannelConfig() {
        return this.channelConfig;
    }

    public void setChannelConfig(final Map<String, Object> channelConfig) {
        this.channelConfig = channelConfig;
    }

    public ChannelStatus getChannelStatus() {
        return this.channelStatus;
    }

    public void setChannelStatus(final ChannelStatus status) {
        this.channelStatus = status;
    }

    public String getChannelName() {
        return this.name;
    }

    public DataType getValueType() {
        return this.valueType;
    }

    public TypedValue<?> getValue() {
        return this.value;
    }

    public void setValue(final TypedValue<?> value) {
        this.value = value;
    }

    public long getTimestamp() {
        return this.timestamp;
    }

    public void setTimestamp(final long timestamp) {
        this.timestamp = timestamp;
    }

    public String getUnit() {
        return this.unit;
    }

    public void setUnit(final String unit) {
        this.unit = unit;
    }

    @Override
    public String toString() {
        return "ChannelRecord{name=" + this.name + ", valueType=" + this.valueType + ", value=" + this.value
                + ", status=" + this.channelStatus + "}";
    }
}
