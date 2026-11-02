/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.regexfilter;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEmitter;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.type.TypedValue;

/**
 * 正则过滤 Wire 组件：按正则匹配 Record 的 key 做 RETAIN / REMOVE 过滤后向下游
 * 发射，对照上游 {@code org.eclipse.kura.internal.wire.regexfilter.RegexFilter}。
 * <p>
 * 行为与上游一致：过滤表达式为空时原样透传；过滤过程中出现任何异常（如正则
 * 非法）时发射未过滤的原始记录；短路分支亦与上游逐字对应 ——
 * {@code allMatch(matches(pattern, type))} 为真时：RETAIN 复用原属性表实例，
 * REMOVE 返回空属性表（上游对「REMOVE 且无 key 命中」同样落到该分支，属上游
 * 既有语义，移植时保留，勿在移植代码中「修正」）。属性 key
 * {@code regex.filter} / {@code filter.type} 与上游 metatype 一致。
 * </p>
 */
public final class RegexFilter implements WireEmitter {

    /** 正则表达式属性 key（与上游一致） */
    public static final String REGEX_PROP = "regex.filter";

    /** 过滤类型属性 key（与上游一致，1 = RETAIN、2 = REMOVE） */
    public static final String REGEX_TYPE_PROP = "filter.type";

    private final String pid;
    private final String filter;
    private final FilterType filterType;
    private final WireEmitContext emitContext;

    /**
     * 创建正则过滤组件实例（由 {@link RegexFilterFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param properties 组件配置属性（已合并默认值）
     * @param emitContext 引擎注入的发射上下文
     */
    RegexFilter(final String pid, final Map<String, Object> properties, final WireEmitContext emitContext) {
        this.pid = pid;
        this.filter = String.valueOf(properties.getOrDefault(REGEX_PROP, ""));
        this.filterType = FilterType
                .fromTypeId(properties.getOrDefault(REGEX_TYPE_PROP, 1) instanceof Integer type ? type : 1);
        this.emitContext = emitContext;
    }

    /** {@inheritDoc} */
    @Override
    public String getPid() {
        return this.pid;
    }

    /**
     * 接收上游信封：按配置过滤记录中的 key 后向下游发射（对照上游 onWireReceive）。
     *
     * @param wireEnvelope 收到的信封
     */
    public void onWireReceive(final WireEnvelope wireEnvelope) {
        requireNonNull(wireEnvelope, "Wire Envelope cannot be null");
        final List<WireRecord> receivedRecords = wireEnvelope.getRecords();
        if (null == this.filter || this.filter.trim().isEmpty()) {
            // 未配置过滤表达式：原样透传
            this.emitContext.emit(new WireEnvelope(this.pid, receivedRecords));
            return;
        }

        final WireEnvelope filteredWireEnvelope = getFilteredWireEnvelope(receivedRecords, wireEnvelope);
        this.emitContext.emit(new WireEnvelope(this.pid, filteredWireEnvelope.getRecords()));
    }

    /**
     * 返回配置的正则表达式（供测试与调试）。
     *
     * @return 正则表达式字符串
     */
    String getFilter() {
        return this.filter;
    }

    /**
     * 返回配置的过滤类型（供测试与调试）。
     *
     * @return 过滤类型
     */
    FilterType getFilterType() {
        return this.filterType;
    }

    private WireEnvelope getFilteredWireEnvelope(final List<WireRecord> wireRecords,
            final WireEnvelope nonFilteredWireEnvelope) {
        WireEnvelope filteredWireEnvelope;
        try {
            filteredWireEnvelope = new WireEnvelope(this.pid, filter(wireRecords, this.filter, this.filterType));
        } catch (final Exception ex) {
            // 过滤出现异常（如正则非法）时，保留上游行为：发射未过滤的原始记录
            filteredWireEnvelope = nonFilteredWireEnvelope;
        }
        return filteredWireEnvelope;
    }

    private static List<WireRecord> filter(final List<WireRecord> wireRecords, final String filter,
            final FilterType type) {
        requireNonNull(wireRecords, "Wire Records cannot be null");
        requireNonNull(filter, "Filter cannot be null");

        final List<WireRecord> filteredWireRecords = new ArrayList<>();
        for (final WireRecord wireRecord : wireRecords) {
            final Map<String, TypedValue<?>> previousProperties = wireRecord.getProperties();
            final Map<String, TypedValue<?>> filteredProperties = match(filter, previousProperties, type);

            // 属性表引用未变说明全量匹配（RETAIN）或属性为空，无需新建 Record（上游优化）
            if (previousProperties == filteredProperties) {
                filteredWireRecords.add(wireRecord);
                continue;
            }
            filteredWireRecords.add(new WireRecord(filteredProperties));
        }
        return filteredWireRecords;
    }

    private static <V> Map<String, V> match(final String regularExpression, final Map<String, V> map,
            final FilterType type) {
        // 属性表为空时无需进入过滤流程（对照上游 match）
        if (map.isEmpty()) {
            return map;
        }
        final Pattern pattern = Pattern.compile(regularExpression);
        final java.util.Set<Map.Entry<String, V>> entrySet = map.entrySet();
        final Stream<java.util.Map.Entry<String, V>> stream = entrySet.stream();
        // 全部 key 都满足谓词时：RETAIN 直接复用原 Map，REMOVE 返回空 Map（上游优化）
        if (stream.allMatch(matches(pattern, type))) {
            return type == FilterType.RETAIN ? map : Collections.unmodifiableMap(new HashMap<>());
        }
        return map.entrySet().stream().filter(matches(pattern, type))
                .collect(Collectors.collectingAndThen(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue),
                        Collections::unmodifiableMap));
    }

    private static <V> Predicate<Map.Entry<String, V>> matches(final Pattern pattern, final FilterType type) {
        final Predicate<Map.Entry<String, V>> predicate = entry -> pattern.matcher(entry.getKey()).matches();
        return type == FilterType.RETAIN ? predicate : predicate.negate();
    }
}
