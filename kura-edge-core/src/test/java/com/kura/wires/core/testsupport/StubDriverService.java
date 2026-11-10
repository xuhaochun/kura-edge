/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.testsupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.driver.DriverService;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.driver.Driver;

/**
 * 测试用驱动服务桩：只想验证「按 pid 找驱动 / 找描述符」这两个协作点。
 */
public final class StubDriverService implements DriverService {

    private final Map<String, Driver> drivers = new LinkedHashMap<>();

    private final Map<String, String> factoryPids = new LinkedHashMap<>();

    private final Map<String, List<Ad>> descriptors = new LinkedHashMap<>();

    /**
     * 注册一个驱动实例。
     *
     * @param pid 实例 PID
     * @param factoryPid 工厂 PID
     * @param driver 实例
     * @return 本对象（链式）
     */
    public StubDriverService withDriver(final String pid, final String factoryPid, final Driver driver) {
        this.drivers.put(pid, driver);
        this.factoryPids.put(pid, factoryPid);
        return this;
    }

    /**
     * 注册工厂的通道描述符。
     *
     * @param factoryPid 工厂 PID
     * @param descriptor 通道参数定义
     * @return 本对象（链式）
     */
    public StubDriverService withDescriptor(final String factoryPid, final List<Ad> descriptor) {
        this.descriptors.put(factoryPid, descriptor);
        return this;
    }

    @Override
    public Collection<String> getDriverFactoryPids() {
        return new ArrayList<>(this.descriptors.keySet());
    }

    @Override
    public boolean isDriverFactory(final String factoryPid) {
        return this.descriptors.containsKey(factoryPid) || this.factoryPids.containsValue(factoryPid);
    }

    @Override
    public Optional<Ocd> getDefinition(final String factoryPid) {
        return Optional.empty();
    }

    @Override
    public List<Ad> getChannelDescriptor(final String factoryPid) {
        return this.descriptors.getOrDefault(factoryPid, List.of());
    }

    @Override
    public Optional<Driver> findDriver(final String pid) {
        return Optional.ofNullable(this.drivers.get(pid));
    }

    @Override
    public Optional<String> getFactoryPid(final String pid) {
        return Optional.ofNullable(this.factoryPids.get(pid));
    }

    @Override
    public boolean exists(final String pid) {
        return this.drivers.containsKey(pid);
    }

    @Override
    public List<String> getDriverPids() {
        return new ArrayList<>(this.drivers.keySet());
    }

    @Override
    public void createDriver(final String factoryPid, final String pid, final Map<String, Object> properties) {
        throw new UnsupportedOperationException("测试桩不实现驱动创建");
    }

    @Override
    public void updateDriver(final String pid, final Map<String, Object> properties) {
        throw new UnsupportedOperationException("测试桩不实现驱动更新");
    }

    @Override
    public void deleteDriver(final String pid) {
        throw new UnsupportedOperationException("测试桩不实现驱动删除");
    }

    @Override
    public void reloadAll() {
        // 测试桩无需恢复
    }
}
