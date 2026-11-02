/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

/**
 * 服务实例标识（pid + factoryPid），对照上游 {@code PidAndFactoryPid}。
 * <p>
 * 本项目为资产条目追加了 {@code driverPid} / {@code description}（上游是让界面自己再取配置，
 * 这里一次给全，避免列表页 N+1 次请求）。驱动条目的这两个字段为 null（{@code non_null} 时省略）。
 * </p>
 */
public class PidAndFactoryPidDto {

    private String pid;

    private String factoryPid;

    private String driverPid;

    private String description;

    public PidAndFactoryPidDto() {
        // Jackson 反序列化用
    }

    public PidAndFactoryPidDto(final String pid, final String factoryPid) {
        this.pid = pid;
        this.factoryPid = factoryPid;
    }

    /**
     * 创建带资产附加信息的条目。
     *
     * @param pid 实例 PID
     * @param factoryPid 工厂 PID
     * @param driverPid 绑定的驱动 PID（驱动条目为 null）
     * @param description 资产描述（驱动条目为 null）
     */
    public PidAndFactoryPidDto(final String pid, final String factoryPid, final String driverPid,
            final String description) {
        this.pid = pid;
        this.factoryPid = factoryPid;
        this.driverPid = driverPid;
        this.description = description;
    }

    public String getPid() {
        return this.pid;
    }

    public String getFactoryPid() {
        return this.factoryPid;
    }

    public String getDriverPid() {
        return this.driverPid;
    }

    public String getDescription() {
        return this.description;
    }
}
