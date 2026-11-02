/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.driver.DriverService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;

/**
 * {@link AssetService} 的默认实现。
 * <p>
 * 资产就是 {@code org.eclipse.kura.wire.WireAsset} 组件的实例，因此实例经
 * {@link WireComponentRegistry} 创建——这样线图引用同一 PID 时复用同一对象
 * （见 {@code SelfConfiguringComponent} 的语义）。
 * </p>
 */
@Service
public class AssetServiceImpl implements AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetServiceImpl.class);

    private final WireComponentRegistry registry;

    private final ComponentConfigurationService configurationService;

    private final DriverService driverService;

    /**
     * 创建服务。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param driverService 驱动服务（解析资产绑定的驱动描述符）
     */
    public AssetServiceImpl(final WireComponentRegistry registry,
            final ComponentConfigurationService configurationService, final DriverService driverService) {
        this.registry = registry;
        this.configurationService = configurationService;
        this.driverService = driverService;
    }

    /**
     * 启动时按既有配置重建资产实例。
     */
    @PostConstruct
    public void init() {
        try {
            reloadAll();
        } catch (final RuntimeException e) {
            log.error("Failed to restore asset instances at startup", e);
        }
    }

    @Override
    public List<String> getAssetPids() {
        return this.configurationService.getConfigurations().stream().filter(AssetServiceImpl::isAssetConfig)
                .map(ComponentConfiguration::getPid).toList();
    }

    @Override
    public boolean exists(final String pid) {
        return pid != null && this.configurationService.getConfiguration(pid)
                .map(AssetServiceImpl::isAssetConfig).orElse(false);
    }

    @Override
    public Optional<Asset> findAsset(final String pid) {
        return this.registry.getInstance(pid).filter(Asset.class::isInstance).map(Asset.class::cast);
    }

    @Override
    public Optional<Map<String, Object>> getAssetProperties(final String pid) {
        return this.configurationService.getConfiguration(pid).filter(AssetServiceImpl::isAssetConfig)
                .map(ComponentConfiguration::getProperties);
    }

    @Override
    public Optional<AssetConfiguration> getAssetConfiguration(final String pid) {
        return getAssetProperties(pid).map(AssetChannelParser::parse);
    }

    @Override
    public void createAsset(final String pid, final Map<String, Object> properties) {
        if (pid == null || pid.isBlank()) {
            throw new WiresException(ErrorCode.VALIDATION_FAILED, "Asset instance PID cannot be blank");
        }
        if (this.configurationService.exists(pid)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "\"" + pid + "\" has already been assigned to an existing instance");
        }
        final Map<String, Object> effective = effectiveProperties(null, properties);
        validateAssetConfiguration(effective);
        this.configurationService.createFactoryConfiguration(AssetConstants.ASSET_FACTORY_PID, pid, effective);
        createInstance(pid, effective);
    }

    @Override
    public void updateAsset(final String pid, final Map<String, Object> properties) {
        if (!exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND,
                    "\"" + pid + "\" does not exist or it is not an Asset instance");
        }
        final Map<String, Object> effective = effectiveProperties(pid, properties);
        validateAssetConfiguration(effective);
        // 上游对 WireAsset 的更新是「删掉工厂配置再按新配置重建」，实例随之重建、监听重新注册
        destroyInstance(pid);
        this.configurationService.updateConfiguration(pid, effective);
        createInstance(pid, effective);
    }

    @Override
    public void deleteAsset(final String pid) {
        if (!exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND,
                    "\"" + pid + "\" does not exist or it is not an Asset instance");
        }
        destroyInstance(pid);
        this.configurationService.deleteFactoryConfiguration(pid);
    }

    @Override
    public List<Ad> getChannelDescriptor(final String pid) {
        final String driverPid = getAssetProperties(pid).map(properties -> {
            final Object raw = properties.get(AssetConstants.ASSET_DRIVER_PROP);
            return raw == null ? null : String.valueOf(raw);
        }).orElse(null);
        final List<Ad> driverAds = driverPid == null ? List.of()
                : this.driverService.getFactoryPid(driverPid).map(this.driverService::getChannelDescriptor)
                        .orElseGet(List::of);
        return AssetChannelDescriptors.merge(driverAds);
    }

    @Override
    public List<Ad> getBaseChannelDescriptor() {
        return AssetChannelDescriptors.base();
    }

    @Override
    public Optional<Ocd> getAssetDefinition() {
        return this.registry.findFactory(AssetConstants.ASSET_FACTORY_PID).map(WireComponentFactory::getDefinition);
    }

    @Override
    public void reloadAll() {
        for (final ComponentConfiguration config : this.configurationService.getConfigurations()) {
            if (!isAssetConfig(config)) {
                continue;
            }
            try {
                createInstance(config.getPid(), config.getProperties());
            } catch (final RuntimeException e) {
                log.error("Failed to create asset instance {}", config.getPid(), e);
            }
        }
    }

    private Map<String, Object> effectiveProperties(final String pid, final Map<String, Object> properties) {
        final Map<String, Object> effective = properties == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(properties);
        effective.put(WireGraphConstants.SERVICE_FACTORY_PID, AssetConstants.ASSET_FACTORY_PID);
        if (!effective.containsKey(AssetConstants.ASSET_DRIVER_PROP) && pid != null) {
            this.configurationService.getConfiguration(pid).ifPresent(config -> {
                final Object existing = config.getProperties().get(AssetConstants.ASSET_DRIVER_PROP);
                if (existing != null) {
                    effective.put(AssetConstants.ASSET_DRIVER_PROP, existing);
                }
            });
        }
        final Object driverPid = effective.get(AssetConstants.ASSET_DRIVER_PROP);
        if (driverPid == null || String.valueOf(driverPid).isBlank()) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "\"" + AssetConstants.ASSET_DRIVER_PROP + "\" property is missing");
        }
        final List<Ad> channelAds = getChannelAdsForDriver(String.valueOf(driverPid));
        return AssetChannelParser.withChannelDefaults(effective, channelAds);
    }

    private List<Ad> getChannelAdsForDriver(final String driverPid) {
        return this.driverService.getFactoryPid(driverPid).map(this.driverService::getChannelDescriptor)
                .map(driverAds -> AssetChannelDescriptors.merge(driverAds)).orElseGet(AssetChannelDescriptors::base);
    }

    private void validateAssetConfiguration(final Map<String, Object> properties) {
        try {
            AssetChannelParser.parse(properties);
        } catch (final IllegalArgumentException e) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "Invalid asset configuration: " + e.getMessage(), e);
        }
    }

    private void createInstance(final String pid, final Map<String, Object> properties) {
        this.registry.createInstance(pid, AssetConstants.ASSET_FACTORY_PID, properties);
    }

    private void destroyInstance(final String pid) {
        this.registry.destroyInstance(pid);
    }

    private static boolean isAssetConfig(final ComponentConfiguration config) {
        final Object factoryPid = config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID);
        return factoryPid != null && AssetConstants.ASSET_FACTORY_PID.equals(String.valueOf(factoryPid));
    }
}
