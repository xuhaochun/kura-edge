/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TickScheduler} 的生产实现：单线程守护调度器（线程名 {@code wire-graph-scheduler}）。
 * <p>
 * 单线程即可满足演示与常规规模：tick 只做「构造信封并投递」，真正的接收端执行在
 * {@code WireGraphEngine} 的投递线程池上，二者分离（Rules 08 §5）。
 * 每个任务都被包一层 try/catch，避免一次异常让 {@code scheduleAtFixedRate} 静默停止后续触发。
 * </p>
 */
final class ScheduledExecutorTickScheduler implements TickScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledExecutorTickScheduler.class);

    private final ScheduledExecutorService executor;

    /** 排程键（组件 pid）→ 当前生效的 future；重复排程按替换处理 */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /**
     * 创建调度器（自建单线程守护执行器）。
     */
    ScheduledExecutorTickScheduler() {
        this(newSingleThreadScheduler());
    }

    /**
     * 创建调度器（测试可注入执行器）。
     *
     * @param executor 底层调度执行器
     */
    ScheduledExecutorTickScheduler(final ScheduledExecutorService executor) {
        this.executor = executor;
    }

    /** {@inheritDoc} */
    @Override
    public void schedulePeriodic(final String pid, final Runnable task, final long initialDelayMillis,
            final long periodMillis) {
        cancel(pid);
        final long period = Math.max(1L, periodMillis);
        this.pending.put(pid, this.executor.scheduleAtFixedRate(guarded(pid, task),
                Math.max(0L, initialDelayMillis), period, TimeUnit.MILLISECONDS));
    }

    /** {@inheritDoc} */
    @Override
    public void scheduleOnce(final String pid, final Runnable task, final long delayMillis) {
        cancel(pid);
        this.pending.put(pid,
                this.executor.schedule(guarded(pid, task), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS));
    }

    /** {@inheritDoc} */
    @Override
    public void cancel(final String pid) {
        final ScheduledFuture<?> future = this.pending.remove(pid);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void cancelAll() {
        for (final String pid : List.copyOf(this.pending.keySet())) {
            cancel(pid);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        cancelAll();
        this.executor.shutdownNow();
    }

    private static Runnable guarded(final String pid, final Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (final Throwable t) {
                // 单次触发失败只记日志：不能让 scheduleAtFixedRate 因异常停止后续触发
                log.error("Scheduled tick for wire component {} failed", pid, t);
            }
        };
    }

    private static ScheduledExecutorService newSingleThreadScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "wire-graph-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }
}
