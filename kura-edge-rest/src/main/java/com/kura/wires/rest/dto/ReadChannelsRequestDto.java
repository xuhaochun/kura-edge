/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.Set;

import jakarta.validation.constraints.NotEmpty;

/**
 * 指定通道读请求（对照上游 {@code ReadRequest}：{@code {"channels":["ch-1"]}}）。
 */
public class ReadChannelsRequestDto {

    @NotEmpty
    private Set<String> channels;

    public Set<String> getChannels() {
        return this.channels;
    }

    public void setChannels(final Set<String> channels) {
        this.channels = channels;
    }
}
