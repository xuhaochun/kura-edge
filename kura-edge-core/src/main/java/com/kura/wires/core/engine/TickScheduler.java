/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

/**
 * 组件调度抽象：{@link WireGraphEngine} 通过它驱动 {@link com.kura.wires.engine.ScheduledEmitter}
 * 的 tick。抽出接口是为了让测试注入可手工驱动的假实现，不依赖真实时钟、不会 flaky。
 * <p>
 * 实现必须保证：同一个 pid 只会有一个生效中的排程（重复排程按「替换」处理）；
 * 单个任务抛出的异常不得中断后续触发（对照上游 Timer 执行器对异常的处理）。
 * </p>
 */
interface TickScheduler {

    /**
     * 按固定速率排程（{@code scheduleAtFixedRate} 语义）。
     *
     * @param pid 组件实例 PID（排程键）
     * @param task 待执行任务
     * @param initialDelayMillis 首次执行前的延迟（毫秒）
     * @param periodMillis 执行周期（毫秒，必须大于 0）
     */
    void schedulePeriodic(String pid, Runnable task, long initialDelayMillis, long periodMillis);

    /**
     * 单次排程（CRON 模式每次触发后重新排下一次）。
     *
     * @param pid 组件实例 PID（排程键）
     * @param task 待执行任务
     * @param delayMillis 执行前的延迟（毫秒）
     */
    void scheduleOnce(String pid, Runnable task, long delayMillis);

    /**
     * 取消指定组件的排程。
     *
     * @param pid 组件实例 PID
     */
    void cancel(String pid);

    /**
     * 取消全部排程。
     */
    void cancelAll();

    /**
     * 关闭调度器并释放线程。
     */
    void shutdown();
}
