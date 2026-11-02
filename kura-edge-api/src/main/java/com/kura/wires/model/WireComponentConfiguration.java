/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

import java.util.Collections;
import java.util.Map;

/**
 * 图内单个组件的配置：组件配置 + 画布渲染等图属性，对照上游
 * {@code org.eclipse.kura.wire.graph.WireComponentConfiguration}。
 * <p>
 * {@code properties} 是图属性（{@code inputPortCount}/{@code outputPortCount}/
 * {@code position.x}/{@code position.y}/{@code inputPortNames.<port>}…），序列化后落在
 * 图 JSON 组件条目的同级字段与 {@code renderingProperties} 里；组件自身的配置属性在
 * {@code configuration} 中，不写进图 JSON（对照上游 marshalComponentProperties）。
 * </p>
 */
public class WireComponentConfiguration {

    private ComponentConfiguration configuration;
    private Map<String, Object> properties = Collections.emptyMap();

    /** 创建空配置（供反序列化使用）。 */
    public WireComponentConfiguration() {
        // Jackson 反序列化用
    }

    /**
     * 创建图内组件配置。
     *
     * @param configuration 组件配置
     * @param properties 图属性（端口数、画布位置等）
     */
    public WireComponentConfiguration(final ComponentConfiguration configuration, final Map<String, Object> properties) {
        this.configuration = configuration;
        this.properties = properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
    }

    public ComponentConfiguration getConfiguration() {
        return this.configuration;
    }

    public void setConfiguration(final ComponentConfiguration configuration) {
        this.configuration = configuration;
    }

    public Map<String, Object> getProperties() {
        return this.properties;
    }

    public void setProperties(final Map<String, Object> properties) {
        this.properties = properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
    }
}
