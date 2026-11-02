/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 整图配置：组件配置列表 + 连线列表，对照上游
 * {@code org.eclipse.kura.wire.graph.WireGraphConfiguration}。
 * <p>
 * 字段名沿用上游 Java 字段名以便对照；序列化后的图 JSON（{@code WireGraph} 属性）顶层键是
 * {@code components} / {@code wires}，由 {@code GraphJsonCodec} 按上游格式写出（Rules 05 §9）。
 * </p>
 */
public class WireGraphConfiguration {

    private List<WireComponentConfiguration> wireComponentConfigurations = new ArrayList<>();
    private List<MultiportWireConfiguration> wireConfigurations = new ArrayList<>();

    /** 创建空图配置（供反序列化使用）。 */
    public WireGraphConfiguration() {
        // Jackson 反序列化用
    }

    /**
     * 创建整图配置。
     *
     * @param wireComponentConfigurations 组件配置列表
     * @param wireConfigurations 连线列表
     */
    public WireGraphConfiguration(final List<WireComponentConfiguration> wireComponentConfigurations,
            final List<MultiportWireConfiguration> wireConfigurations) {
        this.wireComponentConfigurations = wireComponentConfigurations == null ? Collections.emptyList()
                : Collections.unmodifiableList(wireComponentConfigurations);
        this.wireConfigurations = wireConfigurations == null ? Collections.emptyList()
                : Collections.unmodifiableList(wireConfigurations);
    }

    public List<WireComponentConfiguration> getWireComponentConfigurations() {
        return this.wireComponentConfigurations;
    }

    public void setWireComponentConfigurations(final List<WireComponentConfiguration> wireComponentConfigurations) {
        this.wireComponentConfigurations = wireComponentConfigurations == null ? Collections.emptyList()
                : Collections.unmodifiableList(wireComponentConfigurations);
    }

    public List<MultiportWireConfiguration> getWireConfigurations() {
        return this.wireConfigurations;
    }

    public void setWireConfigurations(final List<MultiportWireConfiguration> wireConfigurations) {
        this.wireConfigurations = wireConfigurations == null ? Collections.emptyList()
                : Collections.unmodifiableList(wireConfigurations);
    }
}
