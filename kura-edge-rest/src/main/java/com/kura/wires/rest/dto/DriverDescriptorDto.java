/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;

import com.kura.wires.model.definition.Ad;

/**
 * 驱动通道描述符条目，对照上游 {@code DriverDescriptorDTO}
 * （{@code {"pid","factoryPid","channelDescriptor":[...]}}）。
 */
public class DriverDescriptorDto {

    private String pid;

    private String factoryPid;

    private List<Ad> channelDescriptor;

    public DriverDescriptorDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建描述符条目。
     *
     * @param pid 驱动实例 PID
     * @param factoryPid 驱动工厂 PID
     * @param channelDescriptor 通道参数定义
     */
    public DriverDescriptorDto(final String pid, final String factoryPid, final List<Ad> channelDescriptor) {
        this.pid = pid;
        this.factoryPid = factoryPid;
        this.channelDescriptor = channelDescriptor;
    }

    public String getPid() {
        return this.pid;
    }

    public void setPid(final String pid) {
        this.pid = pid;
    }

    public String getFactoryPid() {
        return this.factoryPid;
    }

    public void setFactoryPid(final String factoryPid) {
        this.factoryPid = factoryPid;
    }

    public List<Ad> getChannelDescriptor() {
        return this.channelDescriptor;
    }

    public void setChannelDescriptor(final List<Ad> channelDescriptor) {
        this.channelDescriptor = channelDescriptor;
    }
}
