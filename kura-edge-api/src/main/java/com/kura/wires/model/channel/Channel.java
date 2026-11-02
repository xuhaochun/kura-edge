/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;

/**
 * 资产通道：一个具名的设备量，附带读写方向、取值类型、scale/offset 与驱动自有配置。
 * 对照上游 {@code org.eclipse.kura.channel.Channel}。
 * <p>
 * {@code configuration} 里的键是<b>去掉 {@code <channelName>#} 前缀</b>后的参数名
 * （如 {@code +type}、{@code modbus.register}），与上游一致——驱动拿到的就是这份配置。
 * </p>
 */
public final class Channel {

    private String name;

    private ChannelType type;

    private DataType valueType;

    private ScaleOffsetType scaleOffsetType;

    private boolean enabled = true;

    private Number valueScale = Double.valueOf(1.0d);

    private Number valueOffset = Double.valueOf(0.0d);

    private String unit;

    private final Map<String, Object> configuration;

    /**
     * 创建通道（对照上游同参数构造器：启用状态与单位随后由 setter 设置）。
     *
     * @param name 通道名
     * @param type 通道类型
     * @param valueType 取值类型
     * @param scaleOffsetType scale/offset 类型
     * @param valueScale scale
     * @param valueOffset offset
     * @param configuration 驱动自有配置（不含通道名前缀）
     */
    public Channel(final String name, final ChannelType type, final DataType valueType,
            final ScaleOffsetType scaleOffsetType, final Number valueScale, final Number valueOffset,
            final Map<String, Object> configuration) {
        this.name = requireNonNull(name, "Channel name cannot be null");
        this.type = requireNonNull(type, "Channel type cannot be null");
        this.valueType = requireNonNull(valueType, "Channel value type cannot be null");
        this.scaleOffsetType = scaleOffsetType == null ? ScaleOffsetType.DEFINED_BY_VALUE_TYPE : scaleOffsetType;
        this.valueScale = valueScale == null ? Double.valueOf(1.0d) : valueScale;
        this.valueOffset = valueOffset == null ? Double.valueOf(0.0d) : valueOffset;
        this.configuration = Collections.unmodifiableMap(
                configuration == null ? new HashMap<>() : new HashMap<>(configuration));
    }

    /**
     * 用该通道配置创建一个「待读」记录（值类型为通道取值类型或 scale/offset 类型）。
     *
     * @return 读记录
     */
    public ChannelRecord createReadRecord() {
        final ChannelRecord record = ChannelRecord.createReadRecord(this.name, effectiveDataType(),
                this.unit == null ? "" : this.unit);
        record.setChannelConfig(this.configuration);
        return record;
    }

    /**
     * 用该通道配置创建一个「待写」记录。
     *
     * @param value 写入值
     * @return 写记录
     */
    public ChannelRecord createWriteRecord(final TypedValue<?> value) {
        final ChannelRecord record = ChannelRecord.createWriteRecord(this.name, this.valueType, value);
        record.setChannelConfig(this.configuration);
        return record;
    }

    /**
     * 驱动应当读写的实际类型：显式 scale/offset 类型时读原始类型，否则用通道取值类型。
     *
     * @return 实际数据类型
     */
    public DataType effectiveDataType() {
        return this.scaleOffsetType == ScaleOffsetType.DEFINED_BY_VALUE_TYPE ? this.valueType
                : this.scaleOffsetType.toDataType();
    }

    public Map<String, Object> getConfiguration() {
        return this.configuration;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public ChannelType getType() {
        return this.type;
    }

    public void setType(final ChannelType type) {
        this.type = type;
    }

    public DataType getValueType() {
        return this.valueType;
    }

    public void setValueType(final DataType valueType) {
        this.valueType = valueType;
    }

    public ScaleOffsetType getScaleOffsetType() {
        return this.scaleOffsetType;
    }

    public void setScaleOffsetType(final ScaleOffsetType scaleOffsetType) {
        this.scaleOffsetType = scaleOffsetType;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public double getValueScale() {
        return this.valueScale.doubleValue();
    }

    public Number getValueScaleAsNumber() {
        return this.valueScale;
    }

    public void setScale(final Number scale) {
        this.valueScale = scale;
    }

    public double getValueOffset() {
        return this.valueOffset.doubleValue();
    }

    public Number getValueOffsetAsNumber() {
        return this.valueOffset;
    }

    public void setOffset(final Number offset) {
        this.valueOffset = offset;
    }

    public String getUnit() {
        return this.unit;
    }

    public void setUnit(final String unit) {
        this.unit = unit;
    }

    @Override
    public String toString() {
        return "Channel{name=" + this.name + ", type=" + this.type + ", valueType=" + this.valueType + "}";
    }
}
