/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建 Asset 实例的请求体。
 * <p>
 * 资产的工厂固定为 {@code org.eclipse.kura.wire.WireAsset}（上游同样如此），因此只需要驱动 PID
 * 与实例 PID；其余属性（描述、{@code emit.*} 选项）可留空，由 OCD 默认值补齐。
 * </p>
 */
public class CreateAssetRequestDto {

    @NotBlank
    private String pid;

    @NotBlank
    private String driverPid;

    private String description;

    private Map<String, Object> properties;

    public String getPid() {
        return this.pid;
    }

    public void setPid(final String pid) {
        this.pid = pid;
    }

    public String getDriverPid() {
        return this.driverPid;
    }

    public void setDriverPid(final String driverPid) {
        this.driverPid = driverPid;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public Map<String, Object> getProperties() {
        return this.properties;
    }

    public void setProperties(final Map<String, Object> properties) {
        this.properties = properties;
    }
}
