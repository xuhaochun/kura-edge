/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.definition;

/**
 * 属性可选值（下拉选项），对照上游 metatype {@code Option}。
 */
public class AdOption {

    private String label;
    private String value;

    /** 创建空选项（供反序列化使用）。 */
    public AdOption() {
        // Jackson 反序列化用
    }

    /**
     * 创建选项。
     *
     * @param label 显示文案
     * @param value 实际取值
     */
    public AdOption(final String label, final String value) {
        this.label = label;
        this.value = value;
    }

    public String getLabel() {
        return this.label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(final String value) {
        this.value = value;
    }
}
