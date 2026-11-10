/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.driver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.config.DefinitionDefaults;
import com.kura.wires.engine.DriverFactory;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.driver.Driver;

/**
 * {@link DriverService} 的默认实现：驱动工厂来自 Spring 注入的 {@link DriverFactory} 列表
 * （每个内置驱动一个 Bean），实例状态保存在内存，配置持久化交给
 * {@link ComponentConfigurationService}（对照上游 ConfigurationAdmin）。
 */
@Service
public class DriverServiceImpl implements DriverService {

    private static final Logger log = LoggerFactory.getLogger(DriverServiceImpl.class);

    private final Map<String, DriverFactory> factories = new LinkedHashMap<>();

    private final Map<String, Driver> instances = new LinkedHashMap<>();

    private final Map<String, String> instanceFactoryPids = new LinkedHashMap<>();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final ComponentConfigurationService configurationService;

    /**
     * 创建服务。
     *
     * @param availableFactories 已注册的驱动工厂
     * @param configurationService 组件配置服务
     */
    public DriverServiceImpl(final List<DriverFactory> availableFactories,
            final ComponentConfigurationService configurationService) {
        this.configurationService = configurationService;
        if (availableFactories != null) {
            for (final DriverFactory factory : availableFactories) {
                this.factories.put(factory.factoryPid(), factory);
            }
        }
    }

    /**
     * 启动时按既有配置重建驱动实例（配置可能来自上一次运行或导入的快照）。
     */
    @PostConstruct
    public void init() {
        try {
            reloadAll();
        } catch (final RuntimeException e) {
            log.error("Failed to restore driver instances at startup", e);
        }
    }

    @Override
    public Collection<String> getDriverFactoryPids() {
        return Collections.unmodifiableSet(this.factories.keySet());
    }

    @Override
    public boolean isDriverFactory(final String factoryPid) {
        return factoryPid != null && this.factories.containsKey(factoryPid);
    }

    @Override
    public Optional<Ocd> getDefinition(final String factoryPid) {
        return findFactory(factoryPid).map(DriverFactory::getDefinition);
    }

    @Override
    public List<Ad> getChannelDescriptor(final String factoryPid) {
        return findFactory(factoryPid).map(factory -> {
            final Object descriptor = factory.getChannelDescriptor() == null ? null
                    : factory.getChannelDescriptor().getDescriptor();
            if (!(descriptor instanceof final List<?> list)) {
                return List.<Ad>of();
            }
            final List<Ad> ads = new ArrayList<>();
            for (final Object item : list) {
                if (item instanceof final Ad ad) {
                    ads.add(ad);
                }
            }
            return ads;
        }).orElseGet(List::of);
    }

    @Override
    public Optional<Driver> findDriver(final String pid) {
        this.lock.readLock().lock();
        try {
            return Optional.ofNullable(this.instances.get(pid));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public Optional<String> getFactoryPid(final String pid) {
        this.lock.readLock().lock();
        try {
            return Optional.ofNullable(this.instanceFactoryPids.get(pid));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public boolean exists(final String pid) {
        if (pid == null || !this.configurationService.exists(pid)) {
            return false;
        }
        return this.configurationService.getConfiguration(pid).map(this::isDriverConfig).orElse(false);
    }

    @Override
    public List<String> getDriverPids() {
        return this.configurationService.getConfigurations().stream().filter(this::isDriverConfig)
                .map(ComponentConfiguration::getPid).toList();
    }

    @Override
    public void createDriver(final String factoryPid, final String pid, final Map<String, Object> properties) {
        final DriverFactory factory = findFactory(factoryPid)
                .orElseThrow(() -> new WiresException(ErrorCode.CONFIG_INVALID,
                        "\"" + factoryPid + "\" is not a registered Driver factory"));
        if (pid == null || pid.isBlank()) {
            throw new WiresException(ErrorCode.VALIDATION_FAILED, "Driver instance PID cannot be blank");
        }
        if (this.configurationService.exists(pid)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "\"" + pid + "\" has already been assigned to an existing instance");
        }
        final Map<String, Object> effective = DefinitionDefaults.apply(factory.getDefinition(), properties);
        effective.put(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid);
        this.configurationService.createFactoryConfiguration(factoryPid, pid, effective);
        createInstance(pid, factoryPid, effective);
    }

    @Override
    public void updateDriver(final String pid, final Map<String, Object> properties) {
        if (!exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND,
                    "\"" + pid + "\" does not exist or it is not a Driver instance");
        }
        final String factoryPid = getFactoryPid(pid)
                .orElseGet(() -> this.configurationService.getConfiguration(pid)
                        .map(config -> String.valueOf(config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID)))
                        .orElse(""));
        final DriverFactory factory = findFactory(factoryPid).orElseThrow(
                () -> new WiresException(ErrorCode.CONFIG_INVALID, "\"" + factoryPid + "\" is not a registered Driver factory"));
        final Map<String, Object> effective = DefinitionDefaults.apply(factory.getDefinition(), properties);
        effective.put(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid);
        this.configurationService.updateConfiguration(pid, effective);
        destroyInstance(pid);
        createInstance(pid, factoryPid, effective);
    }

    @Override
    public void deleteDriver(final String pid) {
        if (!exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND,
                    "\"" + pid + "\" does not exist or it is not a Driver instance");
        }
        destroyInstance(pid);
        this.configurationService.deleteFactoryConfiguration(pid);
    }

    @Override
    public void reloadAll() {
        for (final ComponentConfiguration config : this.configurationService.getConfigurations()) {
            final String factoryPid = factoryPidOf(config);
            if (factoryPid == null || !isDriverFactory(factoryPid)) {
                continue;
            }
            try {
                createInstance(config.getPid(), factoryPid, config.getProperties());
            } catch (final RuntimeException e) {
                log.error("Failed to create driver instance {}", config.getPid(), e);
            }
        }
    }

    private boolean isDriverConfig(final ComponentConfiguration config) {
        return isDriverFactory(factoryPidOf(config));
    }

    private static String factoryPidOf(final ComponentConfiguration config) {
        final Object raw = config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID);
        return raw == null ? null : String.valueOf(raw);
    }

    private void createInstance(final String pid, final String factoryPid, final Map<String, Object> properties) {
        final DriverFactory factory = findFactory(factoryPid).orElseThrow(
                () -> new WiresException(ErrorCode.CONFIG_INVALID, "\"" + factoryPid + "\" is not a registered Driver factory"));
        final Driver driver;
        try {
            driver = factory.create(pid, properties);
        } catch (final RuntimeException e) {
            throw new WiresException(ErrorCode.ENGINE_ERROR, "Failed to create Driver instance " + pid, e);
        }
        this.lock.writeLock().lock();
        try {
            this.instances.put(pid, driver);
            this.instanceFactoryPids.put(pid, factoryPid);
        } finally {
            this.lock.writeLock().unlock();
        }
        log.info("Created Driver instance {} (factory {})", pid, factoryPid);
    }

    private void destroyInstance(final String pid) {
        this.lock.writeLock().lock();
        try {
            this.instances.remove(pid);
            this.instanceFactoryPids.remove(pid);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    private Optional<DriverFactory> findFactory(final String factoryPid) {
        return factoryPid == null ? Optional.empty() : Optional.ofNullable(this.factories.get(factoryPid));
    }
}
