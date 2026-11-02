/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.Map;

import com.kura.wires.model.definition.Ocd;

import jakarta.validation.constraints.NotBlank;

/**
 * 组件配置 DTO，字段名对照上游 {@code ComponentConfigurationDTO}
 * （pid / definition / properties，属性为 {@code {type, value}} 结构）。
 */
public class ComponentConfigurationDto {

    @NotBlank
    private String pid;

    /** 输出时按上游语义去除 definition（null 不序列化） */
    private Ocd definition;

    private Map<String, PropertyDto> properties;

    public ComponentConfigurationDto() {
        // Jackson 反序列化用
    }

    public ComponentConfigurationDto(final String pid, final Ocd definition, final Map<String, PropertyDto> properties) {
        this.pid = pid;
        this.definition = definition;
        this.properties = properties;
    }

    public String getPid() {
        return this.pid;
    }

    public void setPid(final String pid) {
        this.pid = pid;
    }

    public Ocd getDefinition() {
        return this.definition;
    }

    public void setDefinition(final Ocd definition) {
        this.definition = definition;
    }

    public Map<String, PropertyDto> getProperties() {
        return this.properties;
    }

    public void setProperties(final Map<String, PropertyDto> properties) {
        this.properties = properties;
    }
}
