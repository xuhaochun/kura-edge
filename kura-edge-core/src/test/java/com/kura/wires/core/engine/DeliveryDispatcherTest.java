/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 投递器行为（RFC 004）：同一接收组件串行且有序、不同组件互不阻塞、
 * 队列满丢新信封并计数、线程池拒绝后可恢复。
 */
class DeliveryDispatcherTest {

    private ExecutorService pool;

    private DeliveryDispatcher dispatcher;

    private final AtomicInteger concurrent = new AtomicInteger();

    private final AtomicInteger maxConcurrent = new AtomicInteger();

    @AfterEach
    public void tearDown() {
        if (this.dispatcher != null) {
            this.dispatcher.close();
        }
    }

    @Test
    public void successfulSameReceiverRunsSerializedAndInOrder() throws Exception {
        givenDispatcher(4, 16);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch done = new CountDownLatch(3);

        whenSubmittingOrderedTasks("receiver-1", 3, order, done);

        thenAwait(done);
        thenMaxConcurrencyIs(1);
        assertThat(order).containsExactly(0, 1, 2);
        assertThat(this.dispatcher.stats().delivered()).isEqualTo(3L);
        assertThat(this.dispatcher.stats().dropped()).isZero();
    }

    @Test
    public void successfulSlowReceiverDoesNotBlockAnotherReceiver() throws Exception {
        givenDispatcher(4, 16);
        final CountDownLatch slowStarted = new CountDownLatch(1);
        final CountDownLatch slowRelease = new CountDownLatch(1);
        final CountDownLatch fastDone = new CountDownLatch(1);
        final AtomicBoolean slowRunning = new AtomicBoolean();

        whenSubmittingTask("slow-receiver", () -> {
            slowRunning.set(true);
            slowStarted.countDown();
            awaitQuietly(slowRelease);
            slowRunning.set(false);
        });
        thenAwait(slowStarted);
        whenSubmittingTask("fast-receiver", fastDone::countDown);

        thenAwait(fastDone);
        // 慢 lane 仍堵在 release 上，快 lane 却已经投递完成 —— 两条 lane 互不阻塞
        assertThat(slowRunning).as("慢组件应仍在处理中").isTrue();
        slowRelease.countDown();
    }

    @Test
    public void failedFullReceiverQueueDropsNewEnvelopeAndKeepsQueuedOnes() throws Exception {
        givenDispatcher(1, 1);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        whenSubmittingBlockedTask("receiver-1", running, release);
        // 等第一封真正在跑（此时 lane 队列为空），后续「队列满」判定才是确定的
        thenAwait(running);
        whenSubmittingBlockedTask("receiver-1", new CountDownLatch(1), release, done::countDown);
        // 第三个信封进不了容量为 1 的队列：被丢弃，且不会被执行
        whenSubmittingBlockedTask("receiver-1", new CountDownLatch(1), release);

        thenDroppedByReceiverQueueIs(1L);
        release.countDown();
        thenAwait(done);
        thenDeliveredIs(2L);
        thenNoEnvelopeIsLostOrDuplicated();
    }

    @Test
    public void successfulRemovedLaneDropsItsQueuedEnvelopesAndCountsNoDrop() throws Exception {
        givenDispatcher(1, 8);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger queuedTaskRuns = new AtomicInteger();

        whenSubmittingBlockedTask("receiver-1", running, release);
        thenAwait(running);
        whenSubmittingTask("receiver-1", queuedTaskRuns::incrementAndGet);

        this.dispatcher.removeLane("receiver-1");
        release.countDown();
        final CountDownLatch afterRemoval = new CountDownLatch(1);
        whenSubmittingTask("receiver-1", afterRemoval::countDown);
        thenAwait(afterRemoval);

        assertThat(queuedTaskRuns).as("随组件一起作废的信封不应被执行").hasValue(0);
        assertThat(this.dispatcher.stats().dropped()).as("组件移除导致的作废不计入丢弃").isZero();
    }

    @Test
    public void failedPoolRejectionIsCountedAndLaneRecovers() {
        final RecoveringExecutor executor = new RecoveringExecutor();
        this.dispatcher = new DeliveryDispatcher(executor, 8);

        whenSubmittingTask("receiver-1", () -> {
        });
        thenRejectedDrainsIs(1L);
        thenDeliveredIs(0L);
        thenDroppedByReceiverQueueIs(0L);

        executor.reject = false;
        whenSubmittingTask("receiver-1", () -> {
        });

        thenDeliveredIs(2L);
        thenRejectedDrainsIs(1L);
        thenNoEnvelopeIsLostOrDuplicated();
    }

    private void givenDispatcher(final int threads, final int receiverQueueCapacity) {
        this.pool = Executors.newFixedThreadPool(threads);
        this.dispatcher = new DeliveryDispatcher(this.pool, receiverQueueCapacity);
    }

    private void whenSubmittingOrderedTasks(final String pid, final int count, final List<Integer> order,
            final CountDownLatch done) {
        for (int index = 0; index < count; index++) {
            final int sequence = index;
            whenSubmittingTask(pid, () -> {
                final int now = this.concurrent.incrementAndGet();
                this.maxConcurrent.accumulateAndGet(now, Math::max);
                order.add(sequence);
                sleepQuietly(20L);
                this.concurrent.decrementAndGet();
                done.countDown();
            });
        }
    }

    private void whenSubmittingTask(final String pid, final Runnable task) {
        this.dispatcher.submit(pid, task);
    }

    private void whenSubmittingBlockedTask(final String pid, final CountDownLatch started, final CountDownLatch release) {
        whenSubmittingBlockedTask(pid, started, release, () -> {
        });
    }

    private void whenSubmittingBlockedTask(final String pid, final CountDownLatch started, final CountDownLatch release,
            final Runnable afterRelease) {
        whenSubmittingTask(pid, () -> {
            started.countDown();
            awaitQuietly(release);
            afterRelease.run();
        });
    }

    private void thenAwait(final CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5L, TimeUnit.SECONDS)).as("投递应在 5 秒内完成").isTrue();
    }

    private void thenMaxConcurrencyIs(final int expected) {
        assertThat(this.maxConcurrent.get()).isEqualTo(expected);
    }

    private void thenDeliveredIs(final long expected) {
        assertThat(this.dispatcher.stats().delivered()).isEqualTo(expected);
    }

    private void thenDroppedByReceiverQueueIs(final long expected) {
        assertThat(this.dispatcher.stats().droppedByReceiverQueue()).isEqualTo(expected);
    }

    private void thenRejectedDrainsIs(final long expected) {
        assertThat(this.dispatcher.stats().rejectedDrains()).isEqualTo(expected);
    }

    private void thenNoEnvelopeIsLostOrDuplicated() {
        final DeliveryStats stats = this.dispatcher.stats();
        assertThat(stats.submitted() - stats.delivered() - stats.dropped())
                .as("除了正在处理的那个，信封要么已投递、要么已被丢弃：submitted=%s delivered=%s dropped=%s",
                        stats.submitted(), stats.delivered(), stats.dropped())
                .isBetween(0L, 1L);
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(5L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 可切换「拒绝/直接执行」的执行器，用于验证池拒绝后的恢复路径。 */
    private static final class RecoveringExecutor implements Executor {

        private boolean reject = true;

        @Override
        public void execute(final Runnable command) {
            if (this.reject) {
                throw new RejectedExecutionException("pool is full");
            }
            command.run();
        }
    }
}
