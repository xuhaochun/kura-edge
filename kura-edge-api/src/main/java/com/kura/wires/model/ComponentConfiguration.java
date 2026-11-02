/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.kura.wires.model.definition.Ocd;

/**
 * 组件配置（ConfigurationAdmin 子集），对照上游
 * {@code org.eclipse.kura.configuration.ComponentConfiguration}。
 * <p>
 * {@code properties} 中必须包含 {@link WireGraphConstants#SERVICE_FACTORY_PID}
 * （工厂实例才有）；图内组件另有 {@code inputPortCount}/{@code outputPortCount}
 * 属性（对照上游 WireGraphServiceImpl）。
 * </p>
 */
public class ComponentConfiguration {

    private String pid;
    private Ocd definition;
    private Map<String, Object> properties = Collections.emptyMap();

    /** 创建空配置（供反序列化使用）。 */
    public ComponentConfiguration() {
        // Jackson 反序列化用
    }

    /**
     * 创建组件配置。
     *
     * @param pid 组件 PID
     * @param definition 配置描述对象（可为 null）
     * @param properties 配置属性
     */
    public ComponentConfiguration(final String pid, final Ocd definition, final Map<String, Object> properties) {
        this.pid = pid;
        this.definition = definition;
        this.properties = properties == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(properties));
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

    public Map<String, Object> getProperties() {
        return this.properties;
    }

    public void setProperties(final Map<String, Object> properties) {
        this.properties = properties == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(properties));
    }
}
