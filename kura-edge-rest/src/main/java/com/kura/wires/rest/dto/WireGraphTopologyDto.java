/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;

/**
 * 图拓扑 DTO（GET /api/v1/wires/graph/topology 的 data），
 * 顶层字段名 {@code components} / {@code wires} 与上游 {@code WireGraph}
 * 字符串属性 JSON 完全一致（可被 Kura 原生快照导入，禁止改名，Rules 05 §9）。
 * <p>
 * 对照上游 {@code WireRestService#getWireGraphTopology}：上游直接返回该 JSON，
 * 本项目按 Rules 07 统一包一层 {@code {code, message, data}}。
 * </p>
 */
public class WireGraphTopologyDto {

    private List<GraphComponentDto> components;
    private List<GraphWireDto> wires;

    public WireGraphTopologyDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建拓扑 DTO。
     *
     * @param components 组件条目列表
     * @param wires 连线列表
     */
    public WireGraphTopologyDto(final List<GraphComponentDto> components, final List<GraphWireDto> wires) {
        this.components = components;
        this.wires = wires;
    }

    public List<GraphComponentDto> getComponents() {
        return this.components;
    }

    public List<GraphWireDto> getWires() {
        return this.wires;
    }
}
