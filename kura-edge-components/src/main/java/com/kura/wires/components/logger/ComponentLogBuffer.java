/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 组件运行日志环形缓冲：按组件 PID 保存最近 {@value #CAPACITY} 行，
 * 供「日志详情」面板以 tail -f 方式消费。
 * <p>
 * 写端是引擎投递线程（{@link Logger#onWireReceive}），读端是 SSE 推送线程，
 * 因此每个 PID 的双端队列各自加锁、写入为 O(1)；读端**不注册回调**——
 * 推送由 rest 模块的调度器主动拉取（RFC 007），避免在投递线程上做网络 IO。
 * </p>
 */
@Component
public class ComponentLogBuffer {

    /** 每个组件保留的最大行数（RFC 007：超过即丢最旧） */
    public static final int CAPACITY = 100;

    private final Map<String, Deque<ComponentLogLine>> linesByPid = new ConcurrentHashMap<>();

    /** 全局单调序号：跨组件、跨图重载都不重置 */
    private final AtomicLong sequence = new AtomicLong();

    private final Clock clock;

    /** 生产用构造器：UTC 系统时钟。 */
    public ComponentLogBuffer() {
        this(Clock.systemUTC());
    }

    /**
     * 测试用构造器（注入可推进的时钟）。
     *
     * @param clock 时钟
     */
    ComponentLogBuffer(final Clock clock) {
        this.clock = requireNonNull(clock, "Clock cannot be null");
    }

    /**
     * 追加一行日志；超过 {@value #CAPACITY} 行时丢弃最旧的一行。
     *
     * @param pid 组件 PID
     * @param text 日志文本
     */
    public void append(final String pid, final String text) {
        requireNonNull(pid, "Pid cannot be null");
        requireNonNull(text, "Text cannot be null");
        final ComponentLogLine line = new ComponentLogLine(this.sequence.incrementAndGet(), this.clock.instant(), text);
        final Deque<ComponentLogLine> lines = this.linesByPid.computeIfAbsent(pid, key -> new ArrayDeque<>());
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > CAPACITY) {
                lines.removeFirst();
            }
        }
    }

    /**
     * 返回序号大于 {@code sinceSeq} 的行（升序），最多 {@code limit} 行。
     * <p>
     * 若 {@code sinceSeq} 已超过缓冲中的最大序号（后端重启 / 缓冲被清空），
     * 返回当前缓冲内的全部行：消费方据此重新对齐，而不是永久收不到数据。
     * </p>
     *
     * @param pid 组件 PID
     * @param sinceSeq 已消费到的序号（0 表示从头）
     * @param limit 最多返回的行数（&le; 0 时按 {@value #CAPACITY} 处理）
     * @return 增量日志行（只读副本）
     */
    public List<ComponentLogLine> tail(final String pid, final long sinceSeq, final int limit) {
        requireNonNull(pid, "Pid cannot be null");
        final int effectiveLimit = limit <= 0 ? CAPACITY : Math.min(limit, CAPACITY);
        final Deque<ComponentLogLine> lines = this.linesByPid.get(pid);
        if (lines == null) {
            return List.of();
        }
        final List<ComponentLogLine> result = new ArrayList<>();
        synchronized (lines) {
            final ComponentLogLine last = lines.peekLast();
            final boolean rewind = last != null && last.seq() < sinceSeq;
            for (final ComponentLogLine line : lines) {
                if (rewind || line.seq() > sinceSeq) {
                    result.add(line);
                }
            }
        }
        if (result.size() > effectiveLimit) {
            return List.copyOf(result.subList(result.size() - effectiveLimit, result.size()));
        }
        return List.copyOf(result);
    }

    /**
     * 返回该组件缓冲中的最大序号（无数据时为 0）。
     *
     * @param pid 组件 PID
     * @return 最大序号
     */
    public long lastSeq(final String pid) {
        requireNonNull(pid, "Pid cannot be null");
        final Deque<ComponentLogLine> lines = this.linesByPid.get(pid);
        if (lines == null) {
            return 0L;
        }
        synchronized (lines) {
            final ComponentLogLine last = lines.peekLast();
            return last == null ? 0L : last.seq();
        }
    }

    /**
     * 清空该组件的缓冲（组件销毁或测试用）。
     *
     * @param pid 组件 PID
     */
    public void clear(final String pid) {
        requireNonNull(pid, "Pid cannot be null");
        this.linesByPid.remove(pid);
    }
}
