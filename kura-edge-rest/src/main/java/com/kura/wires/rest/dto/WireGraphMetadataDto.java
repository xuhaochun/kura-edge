/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;

import com.kura.wires.model.definition.Ad;

/**
 * GET /metadata 响应体，字段名对照上游 {@code WireGraphMetadata}。
 * <p>
 * 四个列表都可能为 null（{@code non_null} 序列化时省略），与上游 Gson 的「空列表省略」行为一致。
 * 新增字段只能追加（Rules 05 §9）。
 * </p>
 */
public class WireGraphMetadataDto {

    private List<WireComponentDefinitionDto> wireComponentDefinitions;

    private List<ComponentConfigurationDto> driverOCDs;

    private List<DriverDescriptorDto> driverChannelDescriptors;

    private List<Ad> assetChannelDescriptor;

    public WireGraphMetadataDto() {
        // Jackson 反序列化用
    }

    /**
     * 兼容旧调用的构造（只带组件定义）。
     *
     * @param wireComponentDefinitions 组件定义
     */
    public WireGraphMetadataDto(final List<WireComponentDefinitionDto> wireComponentDefinitions) {
        this.wireComponentDefinitions = wireComponentDefinitions;
    }

    /**
     * 创建完整元数据。
     *
     * @param wireComponentDefinitions 组件定义
     * @param driverOCDs 驱动 OCD（{@code pid} = 工厂 PID）
     * @param driverChannelDescriptors 驱动通道描述符
     * @param assetChannelDescriptor 资产基座通道描述符
     */
    public WireGraphMetadataDto(final List<WireComponentDefinitionDto> wireComponentDefinitions,
            final List<ComponentConfigurationDto> driverOCDs, final List<DriverDescriptorDto> driverChannelDescriptors,
            final List<Ad> assetChannelDescriptor) {
        this.wireComponentDefinitions = wireComponentDefinitions;
        this.driverOCDs = driverOCDs;
        this.driverChannelDescriptors = driverChannelDescriptors;
        this.assetChannelDescriptor = assetChannelDescriptor;
    }

    public List<WireComponentDefinitionDto> getWireComponentDefinitions() {
        return this.wireComponentDefinitions;
    }

    public void setWireComponentDefinitions(final List<WireComponentDefinitionDto> wireComponentDefinitions) {
        this.wireComponentDefinitions = wireComponentDefinitions;
    }

    public List<ComponentConfigurationDto> getDriverOCDs() {
        return this.driverOCDs;
    }

    public void setDriverOCDs(final List<ComponentConfigurationDto> driverOCDs) {
        this.driverOCDs = driverOCDs;
    }

    public List<DriverDescriptorDto> getDriverChannelDescriptors() {
        return this.driverChannelDescriptors;
    }

    public void setDriverChannelDescriptors(final List<DriverDescriptorDto> driverChannelDescriptors) {
        this.driverChannelDescriptors = driverChannelDescriptors;
    }

    public List<Ad> getAssetChannelDescriptor() {
        return this.assetChannelDescriptor;
    }

    public void setAssetChannelDescriptor(final List<Ad> assetChannelDescriptor) {
        this.assetChannelDescriptor = assetChannelDescriptor;
    }
}
