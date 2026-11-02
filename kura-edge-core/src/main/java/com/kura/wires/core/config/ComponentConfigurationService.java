/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.model.ComponentConfiguration;

/**
 * 组件配置服务：ConfigurationAdmin「工厂配置」子集的等价物（对照上游
 * {@code org.eclipse.kura.configuration.ConfigurationService} 中被
 * {@code WireGraphServiceImpl} 使用的方法），持久化到数据库。
 */
public interface ComponentConfigurationService {

    /**
     * 创建工厂组件配置（pid 已存在时抛 {@link com.kura.wires.exception.ErrorCode#CONFIG_INVALID}）。
     *
     * @param factoryPid 组件工厂 PID
     * @param pid 组件实例 PID
     * @param properties 配置属性（含 {@code service.factoryPid}）
     */
    void createFactoryConfiguration(String factoryPid, String pid, Map<String, Object> properties);

    /**
     * 更新组件配置属性（pid 不存在时抛 NOT_FOUND）。
     *
     * @param pid 组件实例 PID
     * @param properties 新配置属性（整体替换）
     */
    void updateConfiguration(String pid, Map<String, Object> properties);

    /**
     * 删除组件配置（pid 不存在时抛 NOT_FOUND）。
     *
     * @param pid 组件实例 PID
     */
    void deleteFactoryConfiguration(String pid);

    /**
     * 判断组件配置是否存在。
     *
     * @param pid 组件实例 PID
     * @return 存在时为 true
     */
    boolean exists(String pid);

    /**
     * 按 PID 查找组件配置。
     *
     * @param pid 组件实例 PID
     * @return 组件配置（definition 恒为 null，可从注册表工厂的 OCD 获取）
     */
    Optional<ComponentConfiguration> getConfiguration(String pid);

    /**
     * 返回全部组件配置。
     *
     * @return 组件配置列表
     */
    List<ComponentConfiguration> getConfigurations();
}
