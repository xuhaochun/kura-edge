/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 组件画布位置 DTO，字段名与上游 WireGraph JSON 的
 * {@code components[].renderingProperties.position.{x,y}} 一致。
 * <p>
 * 缺省坐标不输出（{@link JsonInclude.Include#NON_NULL}），与上游
 * {@code marshalPosition} 只写已有属性的行为一致。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphPositionDto {

    private Float x;
    private Float y;

    public GraphPositionDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建位置 DTO。
     *
     * @param x 画布 X 坐标
     * @param y 画布 Y 坐标
     */
    public GraphPositionDto(final Float x, final Float y) {
        this.x = x;
        this.y = y;
    }

    public Float getX() {
        return this.x;
    }

    public Float getY() {
        return this.y;
    }
}
