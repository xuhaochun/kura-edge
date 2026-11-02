/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.config;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 组件配置持久化实体：pid 为主键，属性以 JSON 字符串存储
 * （对照上游 ConfigurationAdmin 的工厂配置存储）。
 */
@Entity
@Table(name = "component_configuration")
public class ComponentConfigurationEntity {

    /** 组件实例 PID（主键，kura.service.pid 语义） */
    @Id
    @Column(name = "pid", nullable = false, length = 255)
    private String pid;

    /** 组件工厂 PID */
    @Column(name = "factory_pid", nullable = false, length = 255)
    private String factoryPid;

    /** 配置属性 JSON（属性名与上游快照一致） */
    @Lob
    @Column(name = "properties_json", nullable = false)
    private String propertiesJson;

    /** JPA 规范要求的无参构造器。 */
    protected ComponentConfigurationEntity() {
        // JPA 反射用
    }

    /**
     * 创建实体。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 组件工厂 PID
     * @param propertiesJson 配置属性 JSON
     */
    public ComponentConfigurationEntity(final String pid, final String factoryPid, final String propertiesJson) {
        this.pid = pid;
        this.factoryPid = factoryPid;
        this.propertiesJson = propertiesJson;
    }

    public String getPid() {
        return this.pid;
    }

    public String getFactoryPid() {
        return this.factoryPid;
    }

    public String getPropertiesJson() {
        return this.propertiesJson;
    }

    public void setPropertiesJson(final String propertiesJson) {
        this.propertiesJson = propertiesJson;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ComponentConfigurationEntity)) {
            return false;
        }
        return Objects.equals(this.pid, ((ComponentConfigurationEntity) obj).pid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.pid);
    }
}
