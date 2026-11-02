/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.dto;

import java.util.List;
import java.util.Map;

import com.kura.wires.model.definition.Ad;

/**
 * Wire 组件定义 DTO，字段名对照上游 {@code WireComponentDefinitionDTO}；
 * 端口上下限在本项目取工厂默认值。
 */
public class WireComponentDefinitionDto {

    private String factoryPid;
    private int minInputPorts;
    private int maxInputPorts;
    private int defaultInputPorts;
    private int minOutputPorts;
    private int maxOutputPorts;
    private int defaultOutputPorts;
    private Map<Integer, String> inputPortNames;
    private Map<Integer, String> outputPortNames;
    private List<Ad> componentOcd;

    public WireComponentDefinitionDto() {
        // Jackson 反序列化用
    }

    /**
     * 创建组件定义 DTO。
     *
     * @param factoryPid 工厂 PID
     * @param minInputPorts 最小输入端口数
     * @param maxInputPorts 最大输入端口数
     * @param defaultInputPorts 默认输入端口数
     * @param minOutputPorts 最小输出端口数
     * @param maxOutputPorts 最大输出端口数
     * @param defaultOutputPorts 默认输出端口数
     * @param inputPortNames 输入端口名
     * @param outputPortNames 输出端口名
     * @param componentOcd 组件配置描述属性定义
     */
    public WireComponentDefinitionDto(final String factoryPid, final int minInputPorts, final int maxInputPorts,
            final int defaultInputPorts, final int minOutputPorts, final int maxOutputPorts,
            final int defaultOutputPorts, final Map<Integer, String> inputPortNames,
            final Map<Integer, String> outputPortNames, final List<Ad> componentOcd) {
        this.factoryPid = factoryPid;
        this.minInputPorts = minInputPorts;
        this.maxInputPorts = maxInputPorts;
        this.defaultInputPorts = defaultInputPorts;
        this.minOutputPorts = minOutputPorts;
        this.maxOutputPorts = maxOutputPorts;
        this.defaultOutputPorts = defaultOutputPorts;
        this.inputPortNames = inputPortNames;
        this.outputPortNames = outputPortNames;
        this.componentOcd = componentOcd;
    }

    public String getFactoryPid() {
        return this.factoryPid;
    }

    public int getMinInputPorts() {
        return this.minInputPorts;
    }

    public int getMaxInputPorts() {
        return this.maxInputPorts;
    }

    public int getDefaultInputPorts() {
        return this.defaultInputPorts;
    }

    public int getMinOutputPorts() {
        return this.minOutputPorts;
    }

    public int getMaxOutputPorts() {
        return this.maxOutputPorts;
    }

    public int getDefaultOutputPorts() {
        return this.defaultOutputPorts;
    }

    public Map<Integer, String> getInputPortNames() {
        return this.inputPortNames;
    }

    public Map<Integer, String> getOutputPortNames() {
        return this.outputPortNames;
    }

    public List<Ad> getComponentOcd() {
        return this.componentOcd;
    }
}
