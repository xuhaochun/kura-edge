/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * 内存版组件配置服务：测试里代替 JPA 实现，保留「pid 唯一、工厂 PID 随配置返回」的真实语义。
 */
public final class InMemoryComponentConfigurationService implements ComponentConfigurationService {

    private final Map<String, ComponentConfiguration> configurations = new LinkedHashMap<>();

    @Override
    public void createFactoryConfiguration(final String factoryPid, final String pid,
            final Map<String, Object> properties) {
        if (this.configurations.containsKey(pid)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "Component configuration " + pid + " already exists");
        }
        final Map<String, Object> stored = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        stored.put(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid);
        this.configurations.put(pid, new ComponentConfiguration(pid, null, stored));
    }

    @Override
    public void updateConfiguration(final String pid, final Map<String, Object> properties) {
        final ComponentConfiguration existing = Optional.ofNullable(this.configurations.get(pid))
                .orElseThrow(() -> new WiresException(ErrorCode.NOT_FOUND,
                        "Component configuration " + pid + " does not exist"));
        final Map<String, Object> merged = properties == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(properties);
        merged.putIfAbsent(WireGraphConstants.SERVICE_FACTORY_PID,
                existing.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID));
        this.configurations.put(pid, new ComponentConfiguration(pid, null, merged));
    }

    @Override
    public void deleteFactoryConfiguration(final String pid) {
        if (this.configurations.remove(pid) == null) {
            throw new WiresException(ErrorCode.NOT_FOUND, "Component configuration " + pid + " does not exist");
        }
    }

    @Override
    public boolean exists(final String pid) {
        return this.configurations.containsKey(pid);
    }

    @Override
    public Optional<ComponentConfiguration> getConfiguration(final String pid) {
        return Optional.ofNullable(this.configurations.get(pid));
    }

    @Override
    public List<ComponentConfiguration> getConfigurations() {
        return new ArrayList<>(this.configurations.values());
    }
}
