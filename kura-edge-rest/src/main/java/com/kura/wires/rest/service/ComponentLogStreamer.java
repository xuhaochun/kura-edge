/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import com.kura.wires.components.logger.ComponentLogBuffer;
import com.kura.wires.components.logger.ComponentLogLine;
import com.kura.wires.rest.dto.ComponentLogLineDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 组件日志 SSE 推送服务（RFC 007）：为「日志详情」面板维护 {@link SseEmitter} 连接，
 * 由单线程守护调度器周期性从 {@link ComponentLogBuffer} 拉取增量并推送。
 * <p>
 * 为什么不让 {@code ComponentLogBuffer.append} 直接回调推送：append 跑在引擎投递线程上
 * （rules/08 的线程模型），在投递线程做 socket IO 会拖慢整图投递。这里把 IO 完全隔离到
 * 独立线程，代价是 ≤ {@value #PUSH_INTERVAL_MILLIS} ms 的推送延迟。
 * </p>
 * <p>
 * 每个连接独立记录 {@code sinceSeq}，因此多个页面同时打开同一组件也不会互相消费；
 * 后端重启后缓冲序号从头开始，{@code lastSeq < sinceSeq} 时自动把游标归零重新对齐。
 * </p>
 */
@Service
public class ComponentLogStreamer {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentLogStreamer.class);

    /** 推送轮询间隔（毫秒）：决定面板的实时感 */
    static final long PUSH_INTERVAL_MILLIS = 200L;

    /** 空闲心跳间隔（毫秒）：防止代理/浏览器把静默连接判死 */
    static final long KEEP_ALIVE_INTERVAL_MILLIS = 15_000L;

    /** SSE 连接最长存活时间（毫秒）：到时自然断开，前端会重连 */
    static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    /** 单次推送的最大行数（与缓冲容量一致） */
    static final int MAX_LINES_PER_PUSH = ComponentLogBuffer.CAPACITY;

    private final ComponentLogBuffer buffer;

    private final ScheduledExecutorService scheduler;

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * 创建推送服务并启动调度器。
     *
     * @param buffer 组件日志环形缓冲
     */
    @Autowired
    public ComponentLogStreamer(final ComponentLogBuffer buffer) {
        this(buffer, newScheduler(), true);
    }

    /**
     * 测试用构造器：{@code startScheduler=false} 时由测试显式调用 {@link #pushPending()}，
     * 避免依赖真实时钟等待。
     *
     * @param buffer 组件日志环形缓冲
     * @param scheduler 调度器（{@code null} 表示不启动）
     * @param startScheduler 是否启动周期推送
     */
    ComponentLogStreamer(final ComponentLogBuffer buffer, final ScheduledExecutorService scheduler,
            final boolean startScheduler) {
        this.buffer = buffer;
        this.scheduler = scheduler;
        if (startScheduler && scheduler != null) {
            scheduler.scheduleAtFixedRate(this::pushPending, PUSH_INTERVAL_MILLIS, PUSH_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 为一个组件建 SSE 订阅。
     *
     * @param pid 组件 PID
     * @param sinceSeq 客户端已消费到的序号（0 表示从头，实际从缓冲现有内容开始）
     * @return SSE 发射器（由 Spring MVC 接管输出）
     */
    public SseEmitter subscribe(final String pid, final long sinceSeq) {
        return register(new Subscription(pid, Math.max(sinceSeq, 0L),
                new SseEmitter(EMITTER_TIMEOUT_MILLIS)));
    }

    /**
     * 测试入口：用给定的发射器注册订阅，便于断言已推送的事件。
     *
     * @param pid 组件 PID
     * @param sinceSeq 已消费序号
     * @param emitter 发射器（测试替身）
     * @return 传入的发射器
     */
    SseEmitter subscribe(final String pid, final long sinceSeq, final SseEmitter emitter) {
        return register(new Subscription(pid, Math.max(sinceSeq, 0L), emitter));
    }

    private SseEmitter register(final Subscription subscription) {
        this.subscriptions.add(subscription);
        subscription.emitter.onCompletion(() -> remove(subscription));
        subscription.emitter.onTimeout(() -> remove(subscription));
        subscription.emitter.onError(error -> remove(subscription));
        LOG.debug("Component log stream subscribed: pid={}, since={}", subscription.pid, subscription.sinceSeq);
        return subscription.emitter;
    }

    /**
     * 向所有订阅推送一次增量（调度器回调，测试也可直接调用）。
     */
    void pushPending() {
        for (final Subscription subscription : this.subscriptions) {
            if (!subscription.push(this.buffer)) {
                remove(subscription);
            }
        }
    }

    private void remove(final Subscription subscription) {
        if (this.subscriptions.remove(subscription)) {
            completeQuietly(subscription.emitter, subscription.pid);
        }
    }

    /** 应用关闭时结束调度与全部连接。 */
    @PreDestroy
    public void shutdown() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
        }
        for (final Subscription subscription : this.subscriptions) {
            completeQuietly(subscription.emitter, subscription.pid);
        }
        this.subscriptions.clear();
    }

    /**
     * 收尾一个已失效的连接。
     * <p>
     * 必须捕获 {@link Exception} 而不只是 {@code RuntimeException}：客户端断开后
     * Spring 的 {@code SseEmitter#complete()} 会抛出 {@code AsyncRequestNotUsableException}
     * （继承自 {@code IOException}，但由框架在无 throws 声明的方法里抛出），
     * 漏掉它会让异常逃逸到调度线程并触发一次无意义的全局错误响应。
     * </p>
     *
     * @param emitter 发射器
     * @param pid 组件 PID（仅用于日志）
     */
    private static void completeQuietly(final SseEmitter emitter, final String pid) {
        try {
            emitter.complete();
        } catch (Exception exception) {
            LOG.debug("Complete SSE emitter failed: pid={}", pid, exception);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "wire-log-sse");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 单个 SSE 连接的状态：PID + 已消费序号 + 上次心跳时间 */
    private static final class Subscription {

        private final String pid;

        private final SseEmitter emitter;

        private long sinceSeq;

        private long lastKeepAlive = System.currentTimeMillis();

        Subscription(final String pid, final long sinceSeq, final SseEmitter emitter) {
            this.pid = pid;
            this.sinceSeq = sinceSeq;
            this.emitter = emitter;
        }

        /**
         * 推送一次增量 + 必要时心跳。
         *
         * @return {@code false} 表示连接已失效，应移除
         */
        boolean push(final ComponentLogBuffer buffer) {
            try {
                final long maxSeq = buffer.lastSeq(this.pid);
                // 后端重启/缓冲清空后序号会倒退：把游标归零重新对齐，避免永久收不到数据
                if (maxSeq < this.sinceSeq) {
                    this.sinceSeq = 0L;
                }
                final List<ComponentLogLine> lines = buffer.tail(this.pid, this.sinceSeq, MAX_LINES_PER_PUSH);
                for (final ComponentLogLine line : lines) {
                    this.emitter.send(SseEmitter.event().name("log").data(toDto(line)));
                    this.sinceSeq = line.seq();
                }
                final long now = System.currentTimeMillis();
                if (now - this.lastKeepAlive >= KEEP_ALIVE_INTERVAL_MILLIS) {
                    this.emitter.send(SseEmitter.event().comment("keep-alive"));
                    this.lastKeepAlive = now;
                }
                return true;
            } catch (IOException | IllegalStateException exception) {
                LOG.debug("Component log stream failed: pid={}", this.pid, exception);
                return false;
            }
        }

        private static ComponentLogLineDto toDto(final ComponentLogLine line) {
            return new ComponentLogLineDto(line.seq(), line.timestamp().toString(), line.text());
        }
    }
}
