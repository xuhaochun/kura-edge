/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kura.wires.core.asset.AssetService;
import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.driver.DriverService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireGraphService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.ComponentConfigurationListDto;
import com.kura.wires.rest.mapper.ConfigDtoMapper;
import org.springframework.stereotype.Service;

/**
 * 组件配置应用服务：/configs/by-pids、/configs 的查询与批量写入编排，
 * 等价迁移上游 {@code WireRestService} 对应端点的校验与 FailureHandler 聚合语义。
 */
@Service
public class WireConfigService {

    private final WireGraphService wireGraphService;
    private final ComponentConfigurationService configurationService;
    private final WireComponentRegistry registry;
    private final DriverService driverService;
    private final AssetService assetService;
    private final ConfigDtoMapper mapper;

    /**
     * 创建服务。
     *
     * @param wireGraphService 图服务
     * @param configurationService 组件配置服务
     * @param registry 组件注册表
     * @param driverService 驱动服务（Driver 配置走驱动服务重建实例）
     * @param assetService 资产服务（Asset 配置走「删建重建」语义）
     * @param mapper 配置映射器
     */
    public WireConfigService(final WireGraphService wireGraphService,
            final ComponentConfigurationService configurationService, final WireComponentRegistry registry,
            final DriverService driverService, final AssetService assetService, final ConfigDtoMapper mapper) {
        this.wireGraphService = wireGraphService;
        this.configurationService = configurationService;
        this.registry = registry;
        this.driverService = driverService;
        this.assetService = assetService;
        this.mapper = mapper;
    }

    /**
     * 批量按 pid 取配置（密码脱敏；不存在的 pid 静默跳过，对照上游 tryAddConfig）。
     *
     * @param pids PID 集合
     * @return 配置 DTO 列表
     */
    public List<ComponentConfigurationDto> getConfigsByPids(final Set<String> pids) {
        final List<ComponentConfigurationDto> result = new ArrayList<>();
        for (final String pid : pids) {
            this.configurationService.getConfiguration(pid)
                    .ifPresent(config -> result.add(this.mapper.toDto(config, true)));
        }
        return result;
    }

    /**
     * 批量删除配置：被图引用的 pid 拒绝（RESOURCE_REFERENCED），不存在的拒绝
     * （NOT_FOUND），删除子任务失败聚合抛出（对照上游 deleteConfigsByPid）。
     *
     * @param pids PID 集合
     */
    public void deleteConfigsByPids(final Set<String> pids) {
        final WireGraphConfiguration graph = this.wireGraphService.get();
        for (final String pid : pids) {
            if (isReferencedByGraph(pid, graph)) {
                throw new WiresException(ErrorCode.RESOURCE_REFERENCED,
                        "\"" + pid + "\" is referenced by the Wire Graph");
            }
            if (!this.configurationService.exists(pid)) {
                throw new WiresException(ErrorCode.NOT_FOUND,
                        "\"" + pid + "\" does not exist or it is not a Driver, Asset or Wire Component instance");
            }
        }
        final FailureHandler handler = new FailureHandler();
        for (final String pid : pids) {
            handler.runFallibleSubtask("delete:" + pid, () -> this.configurationService.deleteFactoryConfiguration(pid));
        }
        handler.checkStatus();
    }

    /**
     * 批量更新配置：校验 factoryPid、Wire Component 必须被图引用，密码占位符还原，
     * 更新子任务失败聚合抛出（对照上游 updateConfigurations）。
     *
     * @param request 配置集合
     */
    public void updateConfigs(final ComponentConfigurationListDto request) {
        final WireGraphConfiguration graph = this.wireGraphService.get();
        for (final ComponentConfigurationDto config : request.getConfigs()) {
            validateConfigForUpdate(config, graph);
        }
        final FailureHandler handler = new FailureHandler();
        for (final ComponentConfigurationDto config : request.getConfigs()) {
            final Map<String, Object> properties = new LinkedHashMap<>(this.mapper.toProperties(config.getProperties()));
            restorePasswordPlaceholders(config, properties);
            final String factoryPid = factoryPidOf(config);
            if (this.driverService.isDriverFactory(factoryPid)) {
                // Driver 配置不能只写库：实例要重建，否则运行期还是旧配置
                handler.runFallibleSubtask("update:" + config.getPid(),
                        () -> this.driverService.updateDriver(config.getPid(), properties));
            } else if (AssetConstants.ASSET_FACTORY_PID.equals(factoryPid)) {
                handler.runFallibleSubtask("update:" + config.getPid(),
                        () -> this.assetService.updateAsset(config.getPid(), properties));
            } else {
                handler.runFallibleSubtask("update:" + config.getPid(),
                        () -> this.configurationService.updateConfiguration(config.getPid(), properties));
            }
        }
        handler.checkStatus();
    }

    private void validateConfigForUpdate(final ComponentConfigurationDto config, final WireGraphConfiguration graph) {
        if (config.getProperties() == null) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "\"" + config.getPid() + "\": configuration properties are not specified.");
        }
        final var rawFactoryPid = config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID);
        if (rawFactoryPid == null || !(rawFactoryPid.getValue() instanceof final String factoryPid)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "\"" + config.getPid() + "\": \""
                    + WireGraphConstants.SERVICE_FACTORY_PID + "\" property is missing or it is not a string.");
        }
        if (isDriverOrAssetFactory(factoryPid)) {
            return;
        }
        if (this.registry.findFactory(factoryPid).isPresent() && !isReferencedByGraph(config.getPid(), graph)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "\"" + config.getPid()
                    + "\" is a Wire Component but it is not referenced by the Wire Graph");
        }
    }

    private void restorePasswordPlaceholders(final ComponentConfigurationDto received,
            final Map<String, Object> properties) {
        final var rawFactoryPid = received.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID);
        final String factoryPid = rawFactoryPid == null ? "" : String.valueOf(rawFactoryPid.getValue());
        for (final Map.Entry<String, com.kura.wires.rest.dto.PropertyDto> entry : received.getProperties().entrySet()) {
            final Object value = entry.getValue() == null ? null : entry.getValue().getValue();
            if (value != null && ConfigDtoMapper.PASSWORD_PLACEHOLDER.equals(String.valueOf(value))
                    && this.mapper.isPasswordProperty(received.getPid(), factoryPid, entry.getKey())) {
                this.configurationService.getConfiguration(received.getPid()).ifPresent(stored -> {
                    final Object real = stored.getProperties().get(entry.getKey());
                    if (real != null) {
                        properties.put(entry.getKey(), real);
                    }
                });
            }
        }
    }

    private boolean isDriverOrAssetFactory(final String factoryPid) {
        return AssetConstants.ASSET_FACTORY_PID.equals(factoryPid) || this.driverService.isDriverFactory(factoryPid);
    }

    private static String factoryPidOf(final ComponentConfigurationDto config) {
        final var property = config.getProperties() == null ? null
                : config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID);
        return property == null ? "" : String.valueOf(property.getValue());
    }

    private static boolean isReferencedByGraph(final String pid, final WireGraphConfiguration graph) {
        return graph.getWireComponentConfigurations().stream()
                .anyMatch(component -> component.getConfiguration() != null
                        && pid.equals(component.getConfiguration().getPid()));
    }
}
