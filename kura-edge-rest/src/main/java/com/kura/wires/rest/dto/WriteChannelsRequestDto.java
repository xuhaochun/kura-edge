/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/**
 * 写通道请求（对照上游 {@code WriteRequestList}：
 * {@code {"channels":[{"name":"c1","type":"INTEGER","value":10}]}}）。
 */
public class WriteChannelsRequestDto {

    @Valid
    @NotEmpty
    private List<WriteChannelDto> channels;

    public List<WriteChannelDto> getChannels() {
        return this.channels;
    }

    public void setChannels(final List<WriteChannelDto> channels) {
        this.channels = channels;
    }
}
