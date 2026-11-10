/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 信封投递器（RFC 004）：有界共享线程池 + 每个接收组件一条串行 lane。
 * <p>
 * 解决三件事：
 * </p>
 * <ol>
 * <li><b>线程数封顶</b>：底层是固定线程数的有界池（由 {@link DeliverySettings} 决定），
 * 不再因为某个组件慢而按到达速率无限建线程；</li>
 * <li><b>同一接收组件串行</b>：每个接收 PID 一条 lane，同一实例任何时刻只有一个线程在跑，
 * 慢组件只堵自己那条边，且处理顺序 = 投递顺序；</li>
 * <li><b>可观测</b>：{@link #stats()} 暴露投递/丢弃计数，丢弃时限流 WARN（队列满丢新信封，
 * 已入队的不会被执行挤出）。</li>
 * </ol>
 * <p>
 * 与上游 Kura WireAdmin 的差异（显式记录，见 RFC 004）：上游是<b>发布者线程内同步直调</b>
 * consumer（equinox.wireadmin `WireImpl#update` → `consumer.updated(...)`，无队列、无线程池），
 * 慢 consumer 会反压发布者、不丢数据；本实现投递与发布者解耦，给每组件设排队上限，
 * 队列满时丢弃新信封，换「进程不被慢组件拖死」。
 * </p>
 */
final class DeliveryDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    /** 丢弃告警的限流间隔：每丢弃这么多信封才打一条 WARN，避免日志自身成为瓶颈 */
    private static final long DROP_WARN_INTERVAL = 100L;

    private final Executor pool;

    private final int receiverQueueCapacity;

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    private final LongAdder submitted = new LongAdder();

    private final LongAdder delivered = new LongAdder();

    private final LongAdder droppedByReceiverQueue = new LongAdder();

    private final LongAdder rejectedDrains = new LongAdder();

    /**
     * 创建投递器。
     *
     * @param pool 信封投递线程池（生产为有界池；测试可注入同步执行器）
     * @param receiverQueueCapacity 每个接收组件的排队上限（&gt; 0）
     */
    DeliveryDispatcher(final Executor pool, final int receiverQueueCapacity) {
        if (receiverQueueCapacity <= 0) {
            throw new IllegalArgumentException("receiverQueueCapacity must be positive");
        }
        this.pool = pool;
        this.receiverQueueCapacity = receiverQueueCapacity;
    }

    /**
     * 投递一个信封任务到指定接收组件的 lane。
     *
     * @param receiverPid 接收组件 PID
     * @param task 实际执行的处理任务（已在内部兜住组件异常）
     */
    void submit(final String receiverPid, final Runnable task) {
        this.submitted.increment();
        this.lanes.computeIfAbsent(receiverPid, Lane::new).submit(task);
    }

    /**
     * 返回当前投递计数快照。
     *
     * @return 计数快照
     */
    DeliveryStats stats() {
        return new DeliveryStats(this.submitted.sum(), this.delivered.sum(),
                this.droppedByReceiverQueue.sum(), this.rejectedDrains.sum());
    }

    /**
     * 移除某个接收组件的 lane（组件实例已销毁时调用），丢弃其中尚未处理完的信封。
     * <p>
     * 这部分信封随组件一起作废，没有可投递的目标，因此**不计入** {@link DeliveryStats#dropped()}；
     * 与「下游处理不过来被丢弃」区分开，避免污染丢数据的观测口径。
     * </p>
     *
     * @param receiverPid 接收组件 PID
     */
    void removeLane(final String receiverPid) {
        final Lane lane = this.lanes.remove(receiverPid);
        if (lane != null) {
            lane.discard();
        }
    }

    /**
     * 移除全部 lane（整图删除时调用）。
     */
    void removeAllLanes() {
        this.lanes.values().forEach(Lane::discard);
        this.lanes.clear();
    }

    @Override
    public void close() {
        this.lanes.clear();
        if (this.pool instanceof final ExecutorService service) {
            service.shutdownNow();
        }
    }

    /** 单个接收组件的串行 lane：队列 + 「是否已有线程在排空」标记。 */
    private final class Lane {

        private final String receiverPid;

        private final Deque<Runnable> tasks = new ArrayDeque<>();

        private boolean draining;

        Lane(final String receiverPid) {
            this.receiverPid = receiverPid;
        }

        void submit(final Runnable task) {
            final boolean dropped;
            synchronized (this) {
                dropped = this.tasks.size() >= DeliveryDispatcher.this.receiverQueueCapacity;
                if (dropped) {
                    DeliveryDispatcher.this.droppedByReceiverQueue.increment();
                } else {
                    this.tasks.add(task);
                    if (this.draining) {
                        return;
                    }
                    this.draining = true;
                }
            }
            // 告警放在锁外：logger 的 appender 可能很慢，别把同 lane 的提交者（tick 线程）堵住
            if (dropped) {
                warnDropped();
                return;
            }
            scheduleDrain();
        }

        /** 组件已从图上移除：清掉排队中的信封（正在执行的那个自然结束）。 */
        void discard() {
            synchronized (this) {
                this.tasks.clear();
            }
        }

        /** 向共享池申请一个线程来排空本 lane（同 lane 至多一个排空任务在飞）。 */
        private void scheduleDrain() {
            try {
                DeliveryDispatcher.this.pool.execute(this::drainLoop);
            } catch (final RejectedExecutionException e) {
                DeliveryDispatcher.this.rejectedDrains.increment();
                synchronized (this) {
                    // 复位标记：任务仍留在队列里，下一次 submit 会重新申请排空
                    this.draining = false;
                }
                log.warn("Delivery pool or queue is full, receiver {} drain postponed to next submission",
                        this.receiverPid, e);
            }
        }

        private void drainLoop() {
            try {
                while (true) {
                    final Runnable next;
                    synchronized (this) {
                        next = this.tasks.poll();
                        if (next == null) {
                            this.draining = false;
                            return;
                        }
                    }
                    DeliveryDispatcher.this.delivered.increment();
                    try {
                        next.run();
                    } catch (final RuntimeException e) {
                        // 任务内部已兜住组件异常，这里只防「投递任务自身」出错
                        log.warn("Delivery task for receiver {} failed", this.receiverPid, e);
                    }
                }
            } catch (final Throwable t) {
                // 组件抛 Error（OOM 之外的 StackOverflowError 等）也必须复位，否则该 lane 永久静默停摆；
                // 与上游 WireAdmin「catch Throwable 后继续投递其它信封」保持一致
                synchronized (this) {
                    this.draining = false;
                }
                log.error("Delivery lane of receiver {} stopped abnormally", this.receiverPid, t);
            }
        }

        private void warnDropped() {
            final long dropped = DeliveryDispatcher.this.droppedByReceiverQueue.sum();
            if (dropped % DROP_WARN_INTERVAL == 1L) {
                log.warn("Receiver {} queue is full ({} pending), dropped {} envelope(s) so far; "
                        + "check the component processing time or raise {}", this.receiverPid,
                        DeliveryDispatcher.this.receiverQueueCapacity, dropped,
                        DeliverySettings.RECEIVER_QUEUE_CAPACITY_PROPERTY);
            }
        }
    }
}
