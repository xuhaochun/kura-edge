/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;

/**
 * 配置默认值回填：按 OCD 定义把缺失的**必填**属性补上默认值。
 * 对照上游 {@code ComponentUtil.getDefaultProperties} 在组件配置创建/绑定驱动时的用法。
 * <p>
 * 只补必填项、不改已有值——上游也是这个语义（非必填项缺省就缺省，UI 显示空即可）。
 * </p>
 */
public final class DefinitionDefaults {

    private DefinitionDefaults() {
        // 工具类
    }

    /**
     * 回填缺失的必填属性默认值。
     *
     * @param definition 组件定义（可为 null，此时原样返回副本）
     * @param properties 已有属性（可为 null）
     * @return 回填后的属性表（新副本）
     */
    public static Map<String, Object> apply(final Ocd definition, final Map<String, Object> properties) {
        final Map<String, Object> result = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        if (definition == null || definition.getAds() == null) {
            return result;
        }
        for (final Ad ad : definition.getAds()) {
            if (ad.isRequired() && ad.getDefaultValue() != null && !result.containsKey(ad.getId())) {
                result.put(ad.getId(), ad.getDefaultValue());
            }
        }
        return result;
    }
}
