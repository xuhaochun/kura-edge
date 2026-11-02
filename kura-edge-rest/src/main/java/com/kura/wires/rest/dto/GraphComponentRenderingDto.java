/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 组件渲染属性 DTO，对照上游 WireGraph JSON 的
 * {@code components[].renderingProperties}（位置 + 端口名）。
 * <p>
 * 端口名在图属性里是 {@code inputPortNames.<port>} / {@code outputPortNames.<port>}，
 * 在 JSON 里是 {@code {"<port>": "<name>"}} 对象（对照上游
 * {@code marshalInputPortNames} / {@code marshalOutputPortNames}）。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphComponentRenderingDto {

    private GraphPositionDto position;
    private Map<String, String> inputPortNames;
    private Map<String, String> outputPortNames;

    public GraphComponentRenderingDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建渲染属性 DTO。
     *
     * @param position 画布位置
     * @param inputPortNames 输入端口名（端口号 → 名称）
     * @param outputPortNames 输出端口名（端口号 → 名称）
     */
    public GraphComponentRenderingDto(final GraphPositionDto position, final Map<String, String> inputPortNames,
            final Map<String, String> outputPortNames) {
        this.position = position;
        this.inputPortNames = inputPortNames;
        this.outputPortNames = outputPortNames;
    }

    public GraphPositionDto getPosition() {
        return this.position;
    }

    public Map<String, String> getInputPortNames() {
        return this.inputPortNames;
    }

    public Map<String, String> getOutputPortNames() {
        return this.outputPortNames;
    }
}
