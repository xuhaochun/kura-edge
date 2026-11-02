/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.core.snapshot.GraphJsonCodec;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.engine.WireGraphService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.ComponentConfigurationListDto;
import com.kura.wires.rest.dto.PropertyDto;
import com.kura.wires.rest.mapper.ConfigDtoMapper;
import org.springframework.stereotype.Service;

/**
 * 整图快照应用服务：GET / PUT /graph/snapshot 与 DELETE /graph 的编排，
 * 等价迁移上游 {@code WireRestService} 的对应流程（含 FailureHandler 聚合语义）。
 */
@Service
public class WireGraphSnapshotService {

    private final WireGraphService wireGraphService;
    private final ComponentConfigurationService configurationService;
    private final WireComponentRegistry registry;
    private final GraphJsonCodec codec;
    private final ConfigDtoMapper mapper;

    /**
     * 创建服务。
     *
     * @param wireGraphService 图服务
     * @param configurationService 组件配置服务
     * @param registry 组件注册表
     * @param codec 图 JSON 编解码器
     * @param mapper 配置映射器
     */
    public WireGraphSnapshotService(final WireGraphService wireGraphService,
            final ComponentConfigurationService configurationService, final WireComponentRegistry registry,
            final GraphJsonCodec codec, final ConfigDtoMapper mapper) {
        this.wireGraphService = wireGraphService;
        this.configurationService = configurationService;
        this.registry = registry;
        this.codec = codec;
        this.mapper = mapper;
    }

    /**
     * 返回当前整图配置。
     *
     * @return 领域整图配置
     */
    public WireGraphConfiguration getGraph() {
        return this.wireGraphService.get();
    }

    /**
     * 删除整图。
     */
    public void deleteGraph() {
        this.wireGraphService.delete();
    }

    /**
     * 组装 GET /graph/snapshot 响应：WireGraphService 配置（含 WireGraph 字符串属性）
     * + 图内组件配置，去除 definition、密码脱敏（对照上游 getWireGraphConfiguration）。
     * <p>
     * 图 JSON 与上游一致只含 PID/端口数/渲染属性，不含组件配置属性，因此密码不会从这条
     * 路径泄漏；组件配置由各自的条目（已按 {@link ConfigDtoMapper} 脱敏）承载。
     * </p>
     *
     * @return 配置 DTO 列表
     */
    public List<ComponentConfigurationDto> getSnapshot() {
        final WireGraphConfiguration graph = this.wireGraphService.get();
        final String marshalled = this.codec.marshal(graph);
        final List<ComponentConfigurationDto> configs = new ArrayList<>();
        configs.add(new ComponentConfigurationDto(WireGraphConstants.WIRE_GRAPH_SERVICE_PID, null,
                Map.of(WireGraphConstants.WIRE_GRAPH_PROPERTY, new PropertyDto("STRING", marshalled))));
        for (final WireComponentConfiguration component : graph.getWireComponentConfigurations()) {
            configs.add(this.mapper.toDto(component.getConfiguration(), true));
        }
        // Driver 配置分组：本项目暂无 Driver 实现，返回前两组即可（对照上游 concat driverConfigs）
        return configs;
    }

    /**
     * 整图保存（PUT /graph/snapshot），对照上游 updateWireGraphConfiguration：
     * 校验 WireGraphService 配置与 WireGraph 属性 → 反序列化图 → 校验 factoryPid →
     * 合并收到的组件配置（还原密码占位符）→ 更新图 → 更新独立配置，失败聚合抛出。
     *
     * @param request 组件配置集合
     */
    public void updateSnapshot(final ComponentConfigurationListDto request) {
        final Map<String, ComponentConfigurationDto> configsByPid = new LinkedHashMap<>();
        for (final ComponentConfigurationDto config : request.getConfigs()) {
            configsByPid.put(config.getPid(), config);
        }
        final ComponentConfigurationDto graphServiceConfig = configsByPid
                .remove(WireGraphConstants.WIRE_GRAPH_SERVICE_PID);
        if (graphServiceConfig == null) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "Configuration for \""
                    + WireGraphConstants.WIRE_GRAPH_SERVICE_PID + "\" must be specified");
        }
        final String marshalledGraph = extractWireGraphProperty(graphServiceConfig);
        final WireGraphConfiguration graph = this.codec.unmarshal(marshalledGraph);

        for (final ComponentConfigurationDto config : configsByPid.values()) {
            validateReceivedConfig(config);
        }

        final List<WireComponentConfiguration> merged = mergeReceivedConfigs(graph, configsByPid);

        final FailureHandler failureHandler = new FailureHandler();
        updateGraphInternal(new WireGraphConfiguration(merged, graph.getWireConfigurations()), failureHandler);
        updateStandaloneConfigs(configsByPid, failureHandler);
        failureHandler.checkStatus();
    }

    private String extractWireGraphProperty(final ComponentConfigurationDto graphServiceConfig) {
        final PropertyDto property = graphServiceConfig.getProperties() == null ? null
                : graphServiceConfig.getProperties().get(WireGraphConstants.WIRE_GRAPH_PROPERTY);
        if (property == null) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "\"" + WireGraphConstants.WIRE_GRAPH_SERVICE_PID
                    + "\" configuration must contain the \"" + WireGraphConstants.WIRE_GRAPH_PROPERTY
                    + "\" property");
        }
        if (!"STRING".equals(property.getType()) || !(property.getValue() instanceof final String value)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "\"" + WireGraphConstants.WIRE_GRAPH_PROPERTY + "\" must be set and of STRING type");
        }
        return value;
    }

    private void validateReceivedConfig(final ComponentConfigurationDto config) {
        if (config.getProperties() == null) {
            throw new WiresException(ErrorCode.CONFIG_INVALID,
                    "\"" + config.getPid() + "\": configuration properties are not specified.");
        }
        final PropertyDto rawFactoryPid = config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID);
        if (rawFactoryPid == null || !"STRING".equals(rawFactoryPid.getType())
                || !(rawFactoryPid.getValue() instanceof final String factoryPid)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "\"" + config.getPid() + "\": \""
                    + WireGraphConstants.SERVICE_FACTORY_PID + "\" property is missing or it is not a string.");
        }
        if (this.registry.findFactory(factoryPid).isEmpty()) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "\"" + config.getPid() + "\": \"" + factoryPid
                    + "\" is not a registered Wire Component, Asset or Driver factory pid.");
        }
    }

    private List<WireComponentConfiguration> mergeReceivedConfigs(final WireGraphConfiguration graph,
            final Map<String, ComponentConfigurationDto> configsByPid) {
        final List<WireComponentConfiguration> merged = new ArrayList<>();
        for (final WireComponentConfiguration component : graph.getWireComponentConfigurations()) {
            final ComponentConfigurationDto received = configsByPid.remove(component.getConfiguration().getPid());
            if (received == null) {
                merged.add(restoreStoredConfiguration(component));
                continue;
            }
            final Map<String, Object> properties = new LinkedHashMap<>(this.mapper.toProperties(received.getProperties()));
            restorePasswordPlaceholders(received, properties);
            merged.add(new WireComponentConfiguration(
                    new ComponentConfiguration(component.getConfiguration().getPid(),
                            component.getConfiguration().getDefinition(), properties),
                    component.getProperties()));
        }
        return merged;
    }

    /**
     * 组件未随请求单独出现时沿用已持久化的配置：图 JSON 与上游一致不含组件配置属性，
     * 故必须从配置服务回填（对照上游 {@code WireGraphServiceImpl#get}）。
     */
    private WireComponentConfiguration restoreStoredConfiguration(final WireComponentConfiguration component) {
        final String pid = component.getConfiguration().getPid();
        return this.configurationService.getConfiguration(pid)
                .map(stored -> new WireComponentConfiguration(stored, component.getProperties()))
                .orElse(component);
    }

    private void updateGraphInternal(final WireGraphConfiguration result, final FailureHandler failureHandler) {
        try {
            this.wireGraphService.update(result);
        } catch (final WiresException e) {
            // 对照上游：BAD_REQUEST 直接抛出，其余进 FailureHandler 聚合
            if (e.getErrorCode().getHttpStatus() < 500) {
                throw e;
            }
            failureHandler.processFailure("updateGraph", e);
        }
    }

    private void updateStandaloneConfigs(final Map<String, ComponentConfigurationDto> configsByPid,
            final FailureHandler failureHandler) {
        for (final ComponentConfigurationDto config : configsByPid.values()) {
            final String factoryPid = String.valueOf(
                    config.getProperties().get(WireGraphConstants.SERVICE_FACTORY_PID).getValue());
            // 未随图出现的 Wire Component 配置不允许独立更新（对照上游 validateConfigForUpdate）
            if (this.registry.findFactory(factoryPid).isPresent()) {
                throw new WiresException(ErrorCode.CONFIG_INVALID, "\"" + config.getPid()
                        + "\" is a Wire Component but it is not referenced by the Wire Graph");
            }
        }
        for (final ComponentConfigurationDto config : configsByPid.values()) {
            final Map<String, Object> properties = this.mapper.toProperties(config.getProperties());
            restorePasswordPlaceholders(config, properties);
            failureHandler.runFallibleSubtask("update:" + config.getPid(),
                    () -> this.configurationService.updateConfiguration(config.getPid(), properties));
        }
    }

    /**
     * 还原收到的密码占位符为已存真实值，避免把 {@code PASSWORD} 字面量写进配置
     * （对照上游加密属性往返语义）。
     *
     * @param received 收到的配置 DTO
     * @param properties 还原目标属性表
     */
    private void restorePasswordPlaceholders(final ComponentConfigurationDto received,
            final Map<String, Object> properties) {
        final Map<String, Object> receivedValues = new LinkedHashMap<>();
        if (received.getProperties() != null) {
            received.getProperties().forEach((key, property) -> receivedValues.put(key,
                    property == null ? null : property.getValue()));
        }
        restorePasswordPlaceholders(received.getPid(), receivedValues, properties);
    }

    /**
     * 还原属性表中的密码占位符（取值表已拆掉 DTO 的 type 包装）。
     *
     * @param pid 组件实例 PID
     * @param receivedValues 收到的属性取值表
     * @param properties 还原目标属性表
     */
    private void restorePasswordPlaceholders(final String pid, final Map<String, Object> receivedValues,
            final Map<String, Object> properties) {
        final String factoryPid = String.valueOf(
                receivedValues.getOrDefault(WireGraphConstants.SERVICE_FACTORY_PID, ""));
        for (final Map.Entry<String, Object> entry : receivedValues.entrySet()) {
            final Object value = entry.getValue();
            if (value != null && ConfigDtoMapper.PASSWORD_PLACEHOLDER.equals(String.valueOf(value))
                    && this.mapper.isPasswordProperty(pid, factoryPid, entry.getKey())) {
                this.configurationService.getConfiguration(pid).ifPresent(stored -> {
                    final Object real = stored.getProperties().get(entry.getKey());
                    if (real != null) {
                        properties.put(entry.getKey(), real);
                    }
                });
            }
        }
    }
}
