/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

import java.time.Instant;

/**
 * 自驱动发射组件：由**引擎**调度 {@link #tick()}，组件自身不建线程、不持定时器
 * （Rules 08 §5）。对照上游 {@code SimpleTimerExecutor} / {@code CronTimerExecutor}
 * 的调度语义：固定速率模式按「首次延迟 + 周期」重复触发，CRON 模式按表达式逐次排程。
 * <p>
 * 引擎在组件实例创建后注册调度、实例销毁或图删除时取消；每次触发都必须由调度方
 * 兜住异常，组件内抛出的异常不得中断后续触发。
 * </p>
 */
public interface ScheduledEmitter {

    /**
     * 是否为固定速率模式（SIMPLE）。返回 false 表示按 {@link #nextCronFireTime(Instant)} 逐次排程。
     *
     * @return 固定速率模式返回 true
     */
    boolean isPeriodic();

    /**
     * 固定速率模式下首次触发前的延迟（毫秒），仅 {@link #isPeriodic()} 为 true 时有效。
     *
     * @return 首次触发延迟毫秒数
     */
    long getInitialDelayMillis();

    /**
     * 固定速率模式下的触发周期（毫秒），仅 {@link #isPeriodic()} 为 true 时有效，必须大于 0。
     *
     * @return 周期毫秒数
     */
    long getPeriodMillis();

    /**
     * CRON 模式下计算 {@code after} 之后的下一次触发时刻，仅 {@link #isPeriodic()} 为 false 时有效。
     *
     * @param after 起始时刻
     * @return 下一次触发时刻
     */
    Instant nextCronFireTime(Instant after);

    /**
     * 触发一次并向图中下游发射。
     */
    void tick();
}
