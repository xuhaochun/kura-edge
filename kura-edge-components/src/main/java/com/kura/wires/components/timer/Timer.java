/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.timer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;

import com.kura.wires.engine.ScheduledEmitter;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEmitter;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.type.LongValue;

/**
 * 定时器 Wire 组件：按配置的间隔触发 tick 事件并向下发射，对照上游
 * {@code org.eclipse.kura.internal.wire.timer.Timer}。
 * <p>
 * 线程模型（Rules 08 §5）：本组件不自建线程池，上游的
 * {@code SimpleTimerExecutor} / {@code CronTimerExecutor} 调度逻辑被拆出——
 * 组件只暴露可直接调用的 {@link #tick()}（发射一条携带当前时间戳的
 * {@code TIMER} 记录），并实现 {@link ScheduledEmitter}，由引擎的 TickScheduler 按
 * {@link #getInitialDelayMillis()} / {@link #getPeriodMillis()}（SIMPLE）或
 * {@link #nextCronFireTime(Instant)}（CRON）驱动，便于测试确定性。
 * </p>
 */
public final class Timer implements WireEmitter, ScheduledEmitter {

    /** 发射记录中承载时间戳的属性 key（与上游一致） */
    public static final String TIMER_PROP = "TIMER";

    private final String pid;
    private final TimerOptions options;
    private final WireEmitContext emitContext;

    /**
     * 创建定时器实例（由 {@link TimerFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param options 定时器配置
     * @param emitContext 引擎注入的发射上下文
     */
    Timer(final String pid, final TimerOptions options, final WireEmitContext emitContext) {
        this.pid = pid;
        this.options = options;
        this.emitContext = emitContext;
    }

    /** {@inheritDoc} */
    @Override
    public String getPid() {
        return this.pid;
    }

    /**
     * 触发一次 tick 并向下游发射（对照上游 static emit(WireSupport)）。
     * 记录内容为 {@code TIMER -> 当前毫秒时间戳}。
     */
    @Override
    public void tick() {
        final WireRecord timerWireRecord =
                new WireRecord(Collections.singletonMap(TIMER_PROP, new LongValue(System.currentTimeMillis())));
        this.emitContext.emit(new WireEnvelope(this.pid, List.of(timerWireRecord)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * SIMPLE 类型走固定速率调度，CRON 类型按表达式逐次排程。
     * </p>
     */
    @Override
    public boolean isPeriodic() {
        return this.options.isSimple();
    }

    /**
     * 返回定时器类型原始配置值（{@code SIMPLE} / {@code CRON}）。
     *
     * @return 类型配置值
     */
    public String getType() {
        return this.options.getType();
    }

    /**
     * 是否为简单间隔模式（对照上游 doUpdate 中的类型分支）。
     *
     * @return SIMPLE 模式返回 true
     */
    public boolean isSimpleType() {
        return this.options.isSimple();
    }

    /**
     * 返回固定触发的周期（毫秒），仅 SIMPLE 类型有效。
     * 调度方按「initialDelay + n * period」调用 {@link #tick()}，等价上游
     * {@code scheduleAtFixedRate} 语义。
     *
     * @return 周期毫秒数（simple.interval × 时间单位乘数）
     * @throws IllegalStateException 类型不是 SIMPLE 时
     */
    @Override
    public long getPeriodMillis() {
        assertSimple();
        return this.options.getSimpleInterval() * this.options.getSimpleTimeUnitMultiplier();
    }

    /**
     * 返回首次触发前的延迟（毫秒），仅 SIMPLE 类型有效（对照上游
     * {@code SimpleTimerExecutor} 的 firstTickInterval 计算）：DEFAULT 策略下
     * 等于周期，CUSTOM 策略下取 {@code simple.custom.first.tick.interval}。
     *
     * @return 首次触发延迟毫秒数
     * @throws IllegalStateException 类型不是 SIMPLE 时
     */
    @Override
    public long getInitialDelayMillis() {
        assertSimple();
        if (this.options.isDefaultFirstTickBehavior()) {
            return getPeriodMillis();
        }
        return this.options.getFirstTickInterval() * this.options.getSimpleTimeUnitMultiplier();
    }

    /**
     * 计算 CRON 类型下 {@code after} 之后的下一次触发时刻（对照上游 Quartz
     * CronTrigger 的计算语义，此处改用 Spring {@link CronExpression}，去 Quartz 依赖）。
     *
     * @param after 起始时刻
     * @return 下一次触发时刻
     * @throws IllegalStateException 类型是 SIMPLE 时（CRON 表达式不参与计算）
     * @throws IllegalArgumentException CRON 表达式非法时
     */
    @Override
    public Instant nextCronFireTime(final Instant after) {
        if (this.options.isSimple()) {
            throw new IllegalStateException("Timer type is not CRON");
        }
        // Spring CronExpression 需要带日历字段的 ZonedDateTime（Instant 会抛
        // UnsupportedTemporalTypeException），统一按 UTC 换算后再取 Instant
        final ZonedDateTime afterUtc = ZonedDateTime.ofInstant(after, ZoneOffset.UTC);
        return CronExpression.parse(this.options.getCronExpression()).next(afterUtc).toInstant();
    }

    private void assertSimple() {
        if (!this.options.isSimple()) {
            throw new IllegalStateException("Timer type is not SIMPLE");
        }
    }
}
