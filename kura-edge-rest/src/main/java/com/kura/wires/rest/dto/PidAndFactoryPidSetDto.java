/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;

/**
 * Driver / Asset 实例列表的线格式，对照上游 {@code PidAndFactoryPidSet}
 * （{@code {"components":[{"pid","factoryPid"}]}}）。
 */
public class PidAndFactoryPidSetDto {

    private List<PidAndFactoryPidDto> components;

    public PidAndFactoryPidSetDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建列表。
     *
     * @param components 实例条目
     */
    public PidAndFactoryPidSetDto(final List<PidAndFactoryPidDto> components) {
        this.components = components;
    }

    public List<PidAndFactoryPidDto> getComponents() {
        return this.components;
    }

    public void setComponents(final List<PidAndFactoryPidDto> components) {
        this.components = components;
    }
}
