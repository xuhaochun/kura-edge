/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.asset.AssetService;
import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.config.DefinitionDefaults;
import com.kura.wires.core.driver.DriverService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireGraphService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.CreateAssetRequestDto;
import com.kura.wires.rest.dto.CreateDriverRequestDto;
import com.kura.wires.rest.dto.DriverDescriptorDto;
import com.kura.wires.rest.dto.PidAndFactoryPidDto;
import com.kura.wires.rest.dto.WireComponentDefinitionDto;
import com.kura.wires.rest.dto.WireGraphMetadataDto;
import org.springframework.stereotype.Service;

/**
 * Drivers and Assets 应用服务：实例 CRUD、引用校验与元数据汇总。
 * <p>
 * 对照上游 GWT 的 {@code GwtDriverAndAssetServiceImpl} 与
 * {@code WireRestService} 的元数据端点；引用的「是否被线图/其它实例占用」校验放在这一层，
 * 因为 core 的驱动/资产服务不该反向依赖图服务。
 * </p>
 */
@Service
public class DriversAssetsService {

    private final DriverService driverService;

    private final AssetService assetService;

    private final WireGraphService wireGraphService;

    private final WireComponentRegistry registry;

    private final ComponentConfigurationService configurationService;

    /**
     * 创建服务。
     *
     * @param driverService 驱动服务
     * @param assetService 资产服务
     * @param wireGraphService 线图服务（删除资产前的引用校验）
     * @param registry 组件注册表（组件定义与通道描述符）
     * @param configurationService 组件配置服务（驱动实例属性查询）
     */
    public DriversAssetsService(final DriverService driverService, final AssetService assetService,
            final WireGraphService wireGraphService, final WireComponentRegistry registry,
            final ComponentConfigurationService configurationService) {
        this.driverService = driverService;
        this.assetService = assetService;
        this.wireGraphService = wireGraphService;
        this.registry = registry;
        this.configurationService = configurationService;
    }

    /**
     * 返回全部 Driver 实例。
     *
     * @return 实例列表（pid + factoryPid）
     */
    public List<PidAndFactoryPidDto> listDrivers() {
        final List<PidAndFactoryPidDto> result = new ArrayList<>();
        for (final String pid : this.driverService.getDriverPids()) {
            this.driverService.getFactoryPid(pid)
                    .ifPresent(factoryPid -> result.add(new PidAndFactoryPidDto(pid, factoryPid)));
        }
        return result;
    }

    /**
     * 返回全部 Asset 实例。
     *
     * @return 实例列表（工厂恒为 {@code org.eclipse.kura.wire.WireAsset}）
     */
    public List<PidAndFactoryPidDto> listAssets() {
        final List<PidAndFactoryPidDto> result = new ArrayList<>();
        for (final String pid : this.assetService.getAssetPids()) {
            final AssetConfiguration configuration = this.assetService.getAssetConfiguration(pid).orElse(null);
            result.add(new PidAndFactoryPidDto(pid, AssetConstants.ASSET_FACTORY_PID,
                    configuration == null ? null : configuration.getDriverPid(),
                    configuration == null ? null : configuration.getDescription()));
        }
        return result;
    }

    /**
     * 返回全部 Wire 组件工厂 PID（含 WireAsset）。
     *
     * @return 工厂 PID
     */
    public List<String> getComponentFactoryPids() {
        return new ArrayList<>(this.registry.getFactoryPids());
    }

    /**
     * 返回驱动工厂 PID 列表（New Driver 下拉的数据源）。
     *
     * @return 工厂 PID
     */
    public List<String> getDriverFactoryPids() {
        return new ArrayList<>(this.driverService.getDriverFactoryPids());
    }

    /**
     * 汇总元数据：组件定义 + 驱动 OCD + 驱动通道描述符 + 资产基座通道描述符。
     *
     * @return 元数据
     */
    public WireGraphMetadataDto getMetadata() {
        final List<WireComponentDefinitionDto> definitions = new ArrayList<>();
        for (final String factoryPid : this.registry.getFactoryPids()) {
            this.registry.findFactory(factoryPid).map(DriversAssetsService::toDefinitionDto).ifPresent(definitions::add);
        }
        final List<ComponentConfigurationDto> driverOcds = new ArrayList<>();
        for (final String factoryPid : this.driverService.getDriverFactoryPids()) {
            this.driverService.getDefinition(factoryPid).ifPresent(definition -> driverOcds
                    .add(new ComponentConfigurationDto(factoryPid, definition, null)));
        }
        final List<DriverDescriptorDto> descriptors = new ArrayList<>();
        for (final String pid : this.driverService.getDriverPids()) {
            this.driverService.getFactoryPid(pid).ifPresent(factoryPid -> descriptors
                    .add(new DriverDescriptorDto(pid, factoryPid, this.driverService.getChannelDescriptor(factoryPid))));
        }
        return new WireGraphMetadataDto(definitions.isEmpty() ? null : definitions,
                driverOcds.isEmpty() ? null : driverOcds,
                descriptors.isEmpty() ? null : descriptors, this.assetService.getBaseChannelDescriptor());
    }

    /**
     * 创建 Driver 实例。
     *
     * @param request 请求体
     */
    public void createDriver(final CreateDriverRequestDto request) {
        this.driverService.createDriver(request.getFactoryPid(), request.getPid(), request.getProperties());
    }

    /**
     * 创建 Asset 实例（工厂固定为 WireAsset，自动写入 {@code driver.pid}）。
     *
     * @param request 请求体
     */
    public void createAsset(final CreateAssetRequestDto request) {
        if (!this.driverService.exists(request.getDriverPid())) {
            throw new WiresException(ErrorCode.NOT_FOUND,
                    "\"" + request.getDriverPid() + "\" does not exist or it is not a Driver instance");
        }
        final Map<String, Object> properties = request.getProperties() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getProperties());
        properties.put(AssetConstants.ASSET_DRIVER_PROP, request.getDriverPid());
        if (request.getDescription() != null) {
            properties.put(AssetConstants.ASSET_DESC_PROP, request.getDescription());
        }
        final Ocd definition = this.assetService.getAssetDefinition().orElse(null);
        final Map<String, Object> withDefaults = DefinitionDefaults.apply(definition, properties);
        this.assetService.createAsset(request.getPid(), withDefaults);
    }

    /**
     * 更新 Driver 配置（重建实例）。
     *
     * @param pid 实例 PID
     * @param properties 新的完整属性
     */
    public void updateDriver(final String pid, final Map<String, Object> properties) {
        this.driverService.updateDriver(pid, properties);
    }

    /**
     * 更新 Asset 配置（删建重建语义）。
     *
     * @param pid 实例 PID
     * @param properties 新的完整属性
     */
    public void updateAsset(final String pid, final Map<String, Object> properties) {
        this.assetService.updateAsset(pid, properties);
    }

    /**
     * 删除 Driver；仍有 Asset 引用时拒绝（409003）。
     *
     * @param pid 实例 PID
     */
    public void deleteDriver(final String pid) {
        final List<String> referencing = assetsOfDriver(pid);
        if (!referencing.isEmpty()) {
            throw new WiresException(ErrorCode.RESOURCE_REFERENCED, "The selected Driver cannot be removed because"
                    + " there are some Asset instances associated with it: " + String.join(", ", referencing));
        }
        this.driverService.deleteDriver(pid);
    }

    /**
     * 删除 Asset；仍被线图引用时拒绝（409003，提示走线图删除）。
     *
     * @param pid 实例 PID
     */
    public void deleteAsset(final String pid) {
        if (isReferencedByGraph(pid)) {
            throw new WiresException(ErrorCode.RESOURCE_REFERENCED,
                    "\"" + pid + "\" is referenced by the Wire Graph");
        }
        this.assetService.deleteAsset(pid);
    }

    /**
     * 返回资产实例的原始配置属性（前端编辑通道表的数据源）。
     *
     * @param pid 资产实例 PID
     * @return 属性表
     */
    public Map<String, Object> getAssetProperties(final String pid) {
        return this.assetService.getAssetProperties(pid)
                .orElseThrow(() -> new WiresException(ErrorCode.NOT_FOUND,
                        "\"" + pid + "\" does not exist or it is not an Asset instance"));
    }

    /**
     * 返回驱动实例的原始配置属性（前端编辑驱动配置的数据源）。
     *
     * @param pid 驱动实例 PID
     * @return 属性表
     */
    public Map<String, Object> getDriverProperties(final String pid) {
        if (!this.driverService.exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND,
                    "\"" + pid + "\" does not exist or it is not a Driver instance");
        }
        return this.configurationService.getConfiguration(pid).map(ComponentConfiguration::getProperties)
                .orElseThrow(() -> new WiresException(ErrorCode.NOT_FOUND, "\"" + pid + "\" does not exist"));
    }

    /**
     * 返回资产展示用的通道描述符（基座 + 该资产绑定的驱动）。
     *
     * @param pid 资产实例 PID
     * @return 通道 AD 列表
     */
    public List<Ad> getAssetChannelDescriptor(final String pid) {
        return this.assetService.getChannelDescriptor(pid);
    }

    /**
     * 返回资产基座通道描述符（与驱动无关的 {@code +} 参数）。
     *
     * @return 基座 AD 列表
     */
    public List<Ad> getBaseChannelDescriptor() {
        return this.assetService.getBaseChannelDescriptor();
    }

    /**
     * 返回指定驱动实例的通道描述符。
     *
     * @param driverPid 驱动实例 PID
     * @return 通道 AD 列表（未知驱动时为空）
     */
    public List<Ad> getDriverChannelDescriptor(final String driverPid) {
        return this.driverService.getFactoryPid(driverPid).map(this.driverService::getChannelDescriptor)
                .orElseGet(List::of);
    }

    /**
     * 返回绑定了某驱动的资产 PID 列表。
     *
     * @param driverPid 驱动实例 PID
     * @return 资产 PID 列表
     */
    public List<String> assetsOfDriver(final String driverPid) {
        final List<String> result = new ArrayList<>();
        for (final String assetPid : this.assetService.getAssetPids()) {
            final Optional<AssetConfiguration> configuration = this.assetService.getAssetConfiguration(assetPid);
            if (configuration.isPresent() && driverPid.equals(configuration.get().getDriverPid())) {
                result.add(assetPid);
            }
        }
        return result;
    }

    /**
     * 资产是否被线图引用。
     *
     * @param pid 资产实例 PID
     * @return 被引用时为 true
     */
    public boolean isReferencedByGraph(final String pid) {
        return isReferencedByGraph(pid, this.wireGraphService.get());
    }

    private static boolean isReferencedByGraph(final String pid, final WireGraphConfiguration graph) {
        return graph != null && graph.getWireComponentConfigurations().stream()
                .anyMatch(component -> component.getConfiguration() != null
                        && pid.equals(component.getConfiguration().getPid()));
    }

    private static WireComponentDefinitionDto toDefinitionDto(final WireComponentFactory factory) {
        final int inputPorts = factory.defaultInputPortCount();
        final int outputPorts = factory.defaultOutputPortCount();
        return new WireComponentDefinitionDto(factory.factoryPid(), inputPorts, inputPorts, inputPorts, outputPorts,
                outputPorts, outputPorts, null, null,
                factory.getDefinition() == null ? null : factory.getDefinition().getAds());
    }
}
