/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.type.DataType;

/**
 * 资产通道的展示模型（对照上游 {@code Channel} 的 REST 序列化形态）。
 * <p>
 * 上游 {@code Channel}#configuration 是 transient、不会序列化；这里同样不暴露通道私有配置。
 * </p>
 */
public class AssetChannelDto {

    private String name;

    private ChannelType type;

    private DataType valueType;

    private ScaleOffsetType scaleOffsetType;

    private Double valueScale;

    private Double valueOffset;

    private String unit;

    private boolean enabled;

    public AssetChannelDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建通道展示模型。
     *
     * @param name 通道名
     * @param type 读写方向
     * @param valueType 取值类型
     * @param scaleOffsetType scale/offset 类型
     * @param valueScale scale
     * @param valueOffset offset
     * @param unit 单位
     * @param enabled 是否启用
     */
    public AssetChannelDto(final String name, final ChannelType type, final DataType valueType,
            final ScaleOffsetType scaleOffsetType, final Double valueScale, final Double valueOffset,
            final String unit, final boolean enabled) {
        this.name = name;
        this.type = type;
        this.valueType = valueType;
        this.scaleOffsetType = scaleOffsetType;
        this.valueScale = valueScale;
        this.valueOffset = valueOffset;
        this.unit = unit;
        this.enabled = enabled;
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

    public Double getValueScale() {
        return this.valueScale;
    }

    public void setValueScale(final Double valueScale) {
        this.valueScale = valueScale;
    }

    public Double getValueOffset() {
        return this.valueOffset;
    }

    public void setValueOffset(final Double valueOffset) {
        this.valueOffset = valueOffset;
    }

    public String getUnit() {
        return this.unit;
    }

    public void setUnit(final String unit) {
        this.unit = unit;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }
}
