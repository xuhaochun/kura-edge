/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.definition;

import java.util.Collections;
import java.util.List;

/**
 * 组件配置属性定义（Attribute Definition），对照上游 metatype
 * {@code org.eclipse.kura.configuration.metatype.AD}。
 * <p>
 * 前端配置区（ConfigurationArea）依据本定义渲染表单与校验
 * （对照 GWT {@code ValidationData}）。
 * </p>
 */
public class Ad {

    private String id;
    private String name;
    private String description;
    private Scalar type = Scalar.STRING;
    private boolean required;
    private Object defaultValue;
    private Double min;
    private Double max;
    private List<AdOption> options = Collections.emptyList();

    /** 创建空定义（供反序列化使用）。 */
    public Ad() {
        // Jackson 反序列化用
    }

    /**
     * 创建属性定义。
     *
     * @param id 属性标识（即配置 key）
     * @param name 显示名
     * @param type 标量类型
     * @param required 是否必填
     * @param defaultValue 默认值
     */
    public Ad(final String id, final String name, final Scalar type, final boolean required, final Object defaultValue) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
    }

    public String getId() {
        return this.id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public Scalar getType() {
        return this.type;
    }

    public void setType(final Scalar type) {
        this.type = type;
    }

    public boolean isRequired() {
        return this.required;
    }

    public void setRequired(final boolean required) {
        this.required = required;
    }

    public Object getDefaultValue() {
        return this.defaultValue;
    }

    public void setDefaultValue(final Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Double getMin() {
        return this.min;
    }

    public void setMin(final Double min) {
        this.min = min;
    }

    public Double getMax() {
        return this.max;
    }

    public void setMax(final Double max) {
        this.max = max;
    }

    public List<AdOption> getOptions() {
        return this.options;
    }

    public void setOptions(final List<AdOption> options) {
        this.options = options == null ? Collections.emptyList() : options;
    }
}
