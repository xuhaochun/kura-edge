/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/**
 * 组件配置集合请求体，对照上游 {@code ComponentConfigurationList}。
 * <p>
 * PUT /graph/snapshot 与 PUT /configs 的请求体；首个约定条目为
 * WireGraphService 自身配置（携带 WireGraph 字符串属性）。
 * </p>
 */
public class ComponentConfigurationListDto {

    @NotEmpty
    @Valid
    private List<ComponentConfigurationDto> configs;

    public ComponentConfigurationListDto() {
        // Jackson 反序列化用
    }

    public ComponentConfigurationListDto(final List<ComponentConfigurationDto> configs) {
        this.configs = configs;
    }

    public List<ComponentConfigurationDto> getConfigs() {
        return this.configs;
    }

    public void setConfigs(final List<ComponentConfigurationDto> configs) {
        this.configs = configs;
    }
}
