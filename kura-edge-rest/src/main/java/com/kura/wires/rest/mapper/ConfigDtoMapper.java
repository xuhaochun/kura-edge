/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.mapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.PropertyDto;
import org.springframework.stereotype.Component;

/**
 * 组件配置 DTO ⇄ 领域模型的手写映射，含密码脱敏
 * （对照上游 {@code DTOUtil.toComponentConfigurationList} 与
 * {@code replacePasswordsWithPlaceholder}，Rules 05 §12）。
 */
@Component
public class ConfigDtoMapper {

    /** 密码占位符，与上游语义一致 */
    public static final String PASSWORD_PLACEHOLDER = "PASSWORD";

    private final WireComponentRegistry registry;

    /**
     * 创建映射器。
     *
     * @param registry 组件注册表（用于按 OCD 判定密码属性）
     */
    public ConfigDtoMapper(final WireComponentRegistry registry) {
        this.registry = registry;
    }

    /**
     * 领域组件配置转 DTO；{@code maskPasswords} 为 true 时把密码属性值替换为占位符。
     *
     * @param configuration 领域配置
     * @param maskPasswords 是否脱敏密码
     * @return 配置 DTO
     */
    public ComponentConfigurationDto toDto(final ComponentConfiguration configuration, final boolean maskPasswords) {
        final String factoryPid = String.valueOf(configuration.getProperties()
                .getOrDefault(WireGraphConstants.SERVICE_FACTORY_PID, ""));
        final Map<String, PropertyDto> properties = new LinkedHashMap<>();
        configuration.getProperties().forEach((key, value) -> {
            String type = scalarTypeOf(value);
            Object payload = value;
            if (maskPasswords && isPasswordProperty(configuration.getPid(), factoryPid, key)) {
                type = Scalar.PASSWORD.name();
                payload = PASSWORD_PLACEHOLDER;
            }
            properties.put(key, new PropertyDto(type, payload));
        });
        return new ComponentConfigurationDto(configuration.getPid(), configuration.getDefinition(), properties);
    }

    /**
     * DTO 属性表还原为原始取值表（去除 type 包装，仅保留 value）。
     *
     * @param properties DTO 属性表
     * @return 原始取值表
     */
    public Map<String, Object> toProperties(final Map<String, PropertyDto> properties) {
        final Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, property) -> result.put(key, property == null ? null : property.getValue()));
        return result;
    }

    /**
     * 判定某属性是否为密码属性：优先取注册表工厂 OCD 中类型为 PASSWORD 的 AD，
     * 工厂未注册或无 OCD 时退回属性名启发式（key 含 "password"，不区分大小写）。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 工厂 PID（可为空串）
     * @param key 属性名
     * @return 是密码属性时为 true
     */
    public boolean isPasswordProperty(final String pid, final String factoryPid, final String key) {
        final Optional<Ocd> ocd = this.registry.findFactory(factoryPid).map(WireComponentFactory::getDefinition)
                .filter(definition -> definition != null);
        final boolean declared = ocd.map(Ocd::getAds)
                .map(ads -> ads.stream().anyMatch(ad -> isPasswordAd(ad, key)))
                .orElse(false);
        return declared || key.toLowerCase().contains("password");
    }

    private static boolean isPasswordAd(final Ad ad, final String key) {
        return ad.getId() != null && ad.getId().equals(key) && ad.getType() == Scalar.PASSWORD;
    }

    private static String scalarTypeOf(final Object value) {
        if (value instanceof String) {
            return Scalar.STRING.name();
        }
        if (value instanceof Integer) {
            return Scalar.INTEGER.name();
        }
        if (value instanceof Long) {
            return Scalar.LONG.name();
        }
        if (value instanceof Boolean) {
            return Scalar.BOOLEAN.name();
        }
        if (value instanceof Double) {
            return Scalar.DOUBLE.name();
        }
        if (value instanceof Float) {
            return Scalar.FLOAT.name();
        }
        if (value instanceof Byte) {
            return Scalar.BYTE.name();
        }
        if (value instanceof Short) {
            return Scalar.SHORT.name();
        }
        if (value instanceof Character) {
            return Scalar.CHAR.name();
        }
        return Scalar.STRING.name();
    }
}
