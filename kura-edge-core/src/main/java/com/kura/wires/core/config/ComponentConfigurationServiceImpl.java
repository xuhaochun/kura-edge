/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * {@link ComponentConfigurationService} 的 JPA 实现：属性序列化为 JSON 存储，
 * 序列化/持久化异常统一转为 {@link WiresException}
 * （对照上游 ConfigurationAdmin 抛 KuraException 的行为）。
 */
@Service
@Transactional
public class ComponentConfigurationServiceImpl implements ComponentConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ComponentConfigurationServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<HashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
        // 反序列化属性表用
    };

    private final ComponentConfigurationCrudRepository repository;

    /**
     * 创建服务。
     *
     * @param repository 组件配置仓库
     */
    public ComponentConfigurationServiceImpl(final ComponentConfigurationCrudRepository repository) {
        this.repository = repository;
    }

    @Override
    public void createFactoryConfiguration(final String factoryPid, final String pid, final Map<String, Object> properties) {
        if (this.repository.existsById(pid)) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "Component configuration " + pid + " already exists");
        }
        try {
            this.repository.save(new ComponentConfigurationEntity(pid, factoryPid, MAPPER.writeValueAsString(properties)));
        } catch (final IOException | RuntimeException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Failed to create component configuration " + pid, e);
        }
        log.info("Created factory configuration {} (factory {})", pid, factoryPid);
    }

    @Override
    public void updateConfiguration(final String pid, final Map<String, Object> properties) {
        final ComponentConfigurationEntity entity = this.repository.findById(pid)
                .orElseThrow(() -> new WiresException(ErrorCode.NOT_FOUND, "Component configuration " + pid + " does not exist"));
        try {
            entity.setPropertiesJson(MAPPER.writeValueAsString(properties));
            this.repository.save(entity);
        } catch (final IOException | RuntimeException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Failed to update component configuration " + pid, e);
        }
        log.info("Updated configuration {}", pid);
    }

    @Override
    public void deleteFactoryConfiguration(final String pid) {
        if (!this.repository.existsById(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND, "Component configuration " + pid + " does not exist");
        }
        try {
            this.repository.deleteById(pid);
        } catch (final RuntimeException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Failed to delete component configuration " + pid, e);
        }
        log.info("Deleted factory configuration {}", pid);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(final String pid) {
        return this.repository.existsById(pid);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComponentConfiguration> getConfiguration(final String pid) {
        return this.repository.findById(pid).map(ComponentConfigurationServiceImpl::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentConfiguration> getConfigurations() {
        return this.repository.findAll().stream().map(ComponentConfigurationServiceImpl::toDomain).toList();
    }

    private static ComponentConfiguration toDomain(final ComponentConfigurationEntity entity) {
        final Map<String, Object> properties = deserialize(entity.getPropertiesJson());
        // 工厂配置必须自带 service.factoryPid（上游 ConfigurationAdmin 的行为）：
        // 驱动/资产据此判定实例类型，图引擎也据此找回工厂，不依赖内存映射
        properties.putIfAbsent(WireGraphConstants.SERVICE_FACTORY_PID, entity.getFactoryPid());
        return new ComponentConfiguration(entity.getPid(), null, properties);
    }

    private static Map<String, Object> deserialize(final String propertiesJson) {
        try {
            return MAPPER.readValue(propertiesJson, MAP_TYPE);
        } catch (final IOException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Corrupted component configuration properties", e);
        }
    }
}
