/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.Set;

import jakarta.validation.constraints.NotEmpty;

/**
 * PID 集合请求体，对照上游 {@code org.eclipse.kura.rest.configuration.api.PidSet}。
 */
public class PidSetDto {

    @NotEmpty
    private Set<String> pids;

    public PidSetDto() {
        // Jackson 反序列化用
    }

    public PidSetDto(final Set<String> pids) {
        this.pids = pids;
    }

    public Set<String> getPids() {
        return this.pids;
    }

    public void setPids(final Set<String> pids) {
        this.pids = pids;
    }
}
