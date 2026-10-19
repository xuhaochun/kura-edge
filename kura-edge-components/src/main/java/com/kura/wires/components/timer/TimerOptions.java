/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.timer;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Timer 组件的可配置项集合，对照上游
 * {@code org.eclipse.kura.internal.wire.timer.TimerOptions}。
 * <p>
 * 属性 key 与上游 metatype（{@code org.eclipse.kura.wire.Timer.xml}）完全一致，
 * 保证 Kura 原生快照导入后配置语义不变：{@code type}、{@code simple.interval}、
 * {@code simple.time.unit}、{@code simple.first.tick.policy}、
 * {@code simple.custom.first.tick.interval}、{@code cron.interval}。
 * </p>
 */
final class TimerOptions {

    /** CRON 表达式属性 key（对照上游 PROP_CRON_INTERVAL） */
    static final String PROP_CRON_INTERVAL = "cron.interval";

    /** 简单间隔属性 key（对照上游 PROP_SIMPLE_INTERVAL） */
    static final String PROP_SIMPLE_INTERVAL = "simple.interval";

    /** 简单间隔的时间单位属性 key（对照上游 PROP_SIMPLE_TIME_UNIT） */
    static final String PROP_SIMPLE_TIME_UNIT = "simple.time.unit";

    /** 首次触发策略属性 key（对照上游 PROP_SIMPLE_TICK_POLICY） */
    static final String PROP_SIMPLE_TICK_POLICY = "simple.first.tick.policy";

    /** 首次触发策略默认值（对照上游 PROP_SIMPLE_TICK_POLICY_DEFAULT_VALUE） */
    static final String PROP_SIMPLE_TICK_POLICY_DEFAULT_VALUE = "DEFAULT";

    /** 自定义首次触发间隔属性 key（对照上游 PROP_SIMPLE_TICK_CUSTOM_INTERVAL） */
    static final String PROP_SIMPLE_TICK_CUSTOM_INTERVAL = "simple.custom.first.tick.interval";

    /** 定时器类型属性 key（SIMPLE / CRON，对照上游 PROP_INTERVAL_TYPE） */
    static final String PROP_INTERVAL_TYPE = "type";

    private final Map<String, Object> properties;

    /**
     * 创建定时器配置。
     *
     * @param properties 组件配置属性（已合并默认值）
     */
    TimerOptions(final Map<String, Object> properties) {
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }

    /**
     * 返回定时器类型（{@code SIMPLE} / {@code CRON}）。
     *
     * @return 类型字符串，未配置时为 {@code null}
     */
    String getType() {
        final Object timerType = this.properties.get(PROP_INTERVAL_TYPE);
        return timerType instanceof String ? (String) timerType : null;
    }

    /**
     * 是否为简单间隔模式（对照上游 doUpdate 中的 {@code "SIMPLE".equalsIgnoreCase(type)}）。
     *
     * @return SIMPLE 模式返回 true，其余（含 CRON）返回 false
     */
    boolean isSimple() {
        return "SIMPLE".equalsIgnoreCase(getType());
    }

    /**
     * 返回简单间隔数值。
     *
     * @return 简单间隔，未配置或类型不符时为 0
     */
    int getSimpleInterval() {
        final Object simpleInterval = this.properties.get(PROP_SIMPLE_INTERVAL);
        return simpleInterval instanceof Integer ? (Integer) simpleInterval : 0;
    }

    /**
     * 返回首次触发前的自定义间隔数值（仅在 CUSTOM 策略下生效）。
     *
     * @return 自定义首次触发间隔，未配置时为 0
     */
    int getFirstTickInterval() {
        final Object firstTickInterval = this.properties.get(PROP_SIMPLE_TICK_CUSTOM_INTERVAL);
        return firstTickInterval instanceof Integer ? (Integer) firstTickInterval : 0;
    }

    /**
     * 是否采用默认首次触发策略（对照上游 {@code isDefaultFirstTickBehavior}）。
     *
     * @return 策略为 DEFAULT（或未配置）时返回 true
     */
    boolean isDefaultFirstTickBehavior() {
        String behavior = PROP_SIMPLE_TICK_POLICY_DEFAULT_VALUE;
        final Object selectedBehavior = this.properties.get(PROP_SIMPLE_TICK_POLICY);
        if (selectedBehavior instanceof String) {
            behavior = (String) selectedBehavior;
        }
        return PROP_SIMPLE_TICK_POLICY_DEFAULT_VALUE.equalsIgnoreCase(behavior);
    }

    /**
     * 返回 CRON 表达式。
     *
     * @return CRON 表达式，未配置时为 {@code null}
     */
    String getCronExpression() {
        final Object interval = this.properties.get(PROP_CRON_INTERVAL);
        return interval instanceof String ? (String) interval : null;
    }

    /**
     * 返回简单间隔换算到毫秒的乘数（对照上游 {@code getSimpleTimeUnitMultiplier}），
     * 时间单位非法时抛出 {@link IllegalArgumentException}（保留上游行为）。
     *
     * @return 一个时间单位对应的毫秒数
     */
    long getSimpleTimeUnitMultiplier() {
        final String timeUnitString = (String) this.properties.getOrDefault(PROP_SIMPLE_TIME_UNIT, "SECONDS");
        final TimeUnit timeUnit;
        if (TimeUnit.MILLISECONDS.name().equals(timeUnitString)) {
            timeUnit = TimeUnit.MILLISECONDS;
        } else if (TimeUnit.SECONDS.name().equals(timeUnitString)) {
            timeUnit = TimeUnit.SECONDS;
        } else if (TimeUnit.MINUTES.name().equals(timeUnitString)) {
            timeUnit = TimeUnit.MINUTES;
        } else if (TimeUnit.HOURS.name().equals(timeUnitString)) {
            timeUnit = TimeUnit.HOURS;
        } else if (TimeUnit.DAYS.name().equals(timeUnitString)) {
            timeUnit = TimeUnit.DAYS;
        } else {
            throw new IllegalArgumentException("Invalid time unit");
        }
        return timeUnit.toMillis(1);
    }
}
