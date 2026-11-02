/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建 Driver 实例的请求体（对照上游 {@code POST /services/configuration/v2/factoryComponents}
 * 的 {@code FactoryComponentConfigurationDTO}）。
 */
public class CreateDriverRequestDto {

    @NotBlank
    private String factoryPid;

    @NotBlank
    private String pid;

    private Map<String, Object> properties;

    public String getFactoryPid() {
        return this.factoryPid;
    }

    public void setFactoryPid(final String factoryPid) {
        this.factoryPid = factoryPid;
    }

    public String getPid() {
        return this.pid;
    }

    public void setPid(final String pid) {
        this.pid = pid;
    }

    public Map<String, Object> getProperties() {
        return this.properties;
    }

    public void setProperties(final Map<String, Object> properties) {
        this.properties = properties;
    }
}
