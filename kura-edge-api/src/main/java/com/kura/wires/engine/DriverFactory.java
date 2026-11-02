/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

import java.util.Map;

import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;

/**
 * 驱动工厂 SPI：每个驱动协议适配器提供一个实现，注册到 core 的驱动服务，
 * 对照上游「DS 组件 + {@code service.factoryPid}」的发现机制。
 * <p>
 * 一个驱动工厂 = 一个可创建的驱动实例类型（如模拟驱动的
 * {@code org.eclipse.kura.driver.simulated}）。
 * </p>
 */
public interface DriverFactory {

    /**
     * 返回驱动工厂 PID（写入实例配置的 {@code service.factoryPid}）。
     *
     * @return 工厂 PID
     */
    String factoryPid();

    /**
     * 返回驱动实例的配置描述（管理界面的驱动表单数据源）。
     *
     * @return OCD
     */
    Ocd getDefinition();

    /**
     * 返回该驱动支持的通道参数描述符。
     *
     * @return 通道描述符
     */
    ChannelDescriptor getChannelDescriptor();

    /**
     * 创建驱动实例。
     *
     * @param pid 驱动实例 PID
     * @param properties 驱动实例配置属性
     * @return 驱动实例
     */
    Driver create(String pid, Map<String, Object> properties);
}
