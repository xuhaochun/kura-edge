/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 拓扑中的组件条目 DTO，对照上游 WireGraph JSON 的 {@code components[]} 项：
 * {@code {pid, inputPortCount, outputPortCount, renderingProperties}}。
 * <p>
 * 图 JSON 只携带拓扑与渲染属性，组件自身的配置属性由 {@code /graph/snapshot} 的
 * 独立配置条目承载（上游 {@code WireGraphJsonMarshallUnmarshallImpl} 同此语义）。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphComponentDto {

    private String pid;
    private Integer inputPortCount;
    private Integer outputPortCount;
    private GraphComponentRenderingDto renderingProperties;

    public GraphComponentDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建组件条目 DTO。
     *
     * @param pid 组件实例 PID
     * @param inputPortCount 输入端口数（Kura 原生快照可省略）
     * @param outputPortCount 输出端口数（Kura 原生快照可省略）
     * @param renderingProperties 渲染属性（位置、端口名）
     */
    public GraphComponentDto(final String pid, final Integer inputPortCount, final Integer outputPortCount,
            final GraphComponentRenderingDto renderingProperties) {
        this.pid = pid;
        this.inputPortCount = inputPortCount;
        this.outputPortCount = outputPortCount;
        this.renderingProperties = renderingProperties;
    }

    public String getPid() {
        return this.pid;
    }

    public Integer getInputPortCount() {
        return this.inputPortCount;
    }

    public Integer getOutputPortCount() {
        return this.outputPortCount;
    }

    public GraphComponentRenderingDto getRenderingProperties() {
        return this.renderingProperties;
    }
}
