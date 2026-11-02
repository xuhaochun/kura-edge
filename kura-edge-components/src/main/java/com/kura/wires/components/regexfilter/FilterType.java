/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.regexfilter;

/**
 * RegexFilter 的过滤类型：RETAIN 保留匹配项、REMOVE 移除匹配项，对照上游
 * {@code org.eclipse.kura.internal.wire.regexfilter.FilterType}。
 * <p>
 * 配置属性 {@code filter.type} 使用上游 metatype 的整数值：1 = RETAIN、2 = REMOVE。
 * </p>
 */
enum FilterType {

    /** 保留匹配正则的 key（filter.type = 1，默认） */
    RETAIN,
    /** 移除匹配正则的 key（filter.type = 2） */
    REMOVE;

    /**
     * 按上游 metatype 的整数值解析过滤类型。
     *
     * @param typeId {@code filter.type} 属性值（1 或 2）
     * @return 对应的过滤类型
     */
    static FilterType fromTypeId(final int typeId) {
        return typeId == 2 ? REMOVE : RETAIN;
    }
}
