/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ScheduledExecutorTickScheduler} 的行为测试：任务异常不中断周期触发、
 * 取消后不再触发、关闭后释放执行器。
 */
class ScheduledExecutorTickSchedulerTest {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

    @AfterEach
    public void tearDown() {
        this.executor.shutdownNow();
    }

    @Test
    public void successfulPeriodicTicksKeepRunningAfterTaskFailure() throws InterruptedException {
        final ScheduledExecutorTickScheduler scheduler = new ScheduledExecutorTickScheduler(this.executor);
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch threeRuns = new CountDownLatch(3);

        scheduler.schedulePeriodic("timer-1", () -> {
            if (runs.incrementAndGet() <= 2) {
                throw new IllegalStateException("boom");
            }
            threeRuns.countDown();
        }, 0L, 5L);

        assertThat(threeRuns.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void successfulCancelStopsFurtherTicks() throws InterruptedException {
        final ScheduledExecutorTickScheduler scheduler = new ScheduledExecutorTickScheduler(this.executor);
        final AtomicInteger runs = new AtomicInteger();

        scheduler.schedulePeriodic("timer-1", runs::incrementAndGet, 0L, 5L);
        waitUntil(() -> runs.get() >= 2);
        scheduler.cancel("timer-1");
        final int afterCancel = runs.get();
        Thread.sleep(100L);

        assertThat(runs.get()).isEqualTo(afterCancel);
    }

    @Test
    public void successfulShutdownCancelsAndStopsExecutor() {
        final ScheduledExecutorTickScheduler scheduler = new ScheduledExecutorTickScheduler(this.executor);

        scheduler.scheduleOnce("cron-1", () -> {
        }, 10L);
        scheduler.shutdown();

        assertThat(this.executor.isShutdown()).isTrue();
    }

    private static void waitUntil(final java.util.function.BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("condition not met within timeout");
    }
}
