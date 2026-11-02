/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.fifo;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.engine.DrainableComponent;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEmitter;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireReceiver;

/**
 * FIFO Wire 组件：把收到的 {@link WireEnvelope} 按到达顺序入队，再按 FIFO
 * 顺序向下游发射，对照上游 {@code org.eclipse.kura.internal.wire.fifo.Fifo}。
 * <p>
 * 上游用一条专用 {@code FifoEmitterThread} 消费队列；按 Rules 08 §5（组件禁止
 * 自建线程池），本实现把「消费并发射」拆成可被引擎直接调用的 {@link #drain()}，
 * 发射语义与上游一致：队列非空时按入队顺序逐个发射。容量语义与上游一致：
 * 队列满时若 {@code discard.envelopes = true} 丢弃新信封；否则（上游的阻塞
 * 生产者语义，同步模型下不可阻塞）立即发射队头信封腾出容量再入队，保证
 * 信封不丢且顺序不变。属性 key 与上游 metatype
 * （{@code org.eclipse.kura.wire.Fifo.xml}）一致：{@code queue.capacity}、
 * {@code discard.envelopes}。
 * </p>
 */
public final class Fifo implements WireEmitter, WireReceiver, DrainableComponent {

    /** 队列容量属性 key（与上游一致） */
    public static final String PROP_QUEUE_CAPACITY = "queue.capacity";

    /** 队列满时是否丢弃新信封属性 key（与上游一致） */
    public static final String PROP_DISCARD_ENVELOPES = "discard.envelopes";

    private static final int DEFAULT_QUEUE_CAPACITY = 50;

    private static final Logger LOG = LoggerFactory.getLogger(Fifo.class);

    private final String pid;
    private final WireEmitContext emitContext;
    private final ArrayDeque<WireEnvelope> queue = new ArrayDeque<>();

    /** 配置可经 updated() 热更新（对照上游 updated 回调），故为 volatile */
    private volatile Map<String, Object> properties;

    /**
     * 创建 FIFO 组件实例（由 {@link FifoFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param properties 组件配置属性（已合并默认值）
     * @param emitContext 引擎注入的发射上下文
     */
    Fifo(final String pid, final Map<String, Object> properties, final WireEmitContext emitContext) {
        this.pid = pid;
        this.properties = requireNonNull(properties, "Properties cannot be null");
        this.emitContext = emitContext;
    }

    /** {@inheritDoc} */
    @Override
    public String getPid() {
        return this.pid;
    }

    /**
     * 接收上游信封：按容量与丢弃策略入队（对照上游 onWireReceive → submit）。
     *
     * @param wireEnvelope 收到的信封
     */
    @Override
    public void onWireReceive(final WireEnvelope wireEnvelope) {
        requireNonNull(wireEnvelope, "Wire Envelope cannot be null");
        WireEnvelope overflowed = null;
        synchronized (this) {
            if (this.queue.size() >= getQueueCapacity()) {
                if (isDiscardEnvelopes()) {
                    LOG.debug("[{}] queue full, incoming envelope discarded", this.pid);
                    return;
                }
                // 队列满且不丢弃：立即发射队头腾出容量（等价上游阻塞生产者被消费唤醒）
                overflowed = this.queue.pollFirst();
            }
            this.queue.addLast(wireEnvelope);
        }
        if (overflowed != null) {
            LOG.debug("[{}] queue full, emitting oldest envelope to make room", this.pid);
            this.emitContext.emit(overflowed);
        }
    }

    /**
     * 把队列中的信封按 FIFO 顺序全部发射到下游（对照上游 FifoEmitterThread 的
     * 消费循环）。实现 {@link DrainableComponent}：由引擎在投递执行器上异步调用，
     * 组件不自建消费线程（Rules 08 §5）。
     */
    @Override
    public void drain() {
        final List<WireEnvelope> drained = new ArrayList<>();
        synchronized (this) {
            while (!this.queue.isEmpty()) {
                drained.add(this.queue.pollFirst());
            }
        }
        for (final WireEnvelope envelope : drained) {
            this.emitContext.emit(envelope);
        }
    }

    /**
     * 返回当前排队中的信封数（供测试与引擎监控）。
     *
     * @return 排队信封数
     */
    public synchronized int getQueuedCount() {
        return this.queue.size();
    }

    /**
     * 热更新组件配置（对照上游 OSGi updated 回调）。
     *
     * @param properties 新的配置属性
     */
    public void updated(final Map<String, Object> properties) {
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }

    private int getQueueCapacity() {
        final Object capacity = this.properties.get(PROP_QUEUE_CAPACITY);
        return capacity instanceof Integer ? (Integer) capacity : DEFAULT_QUEUE_CAPACITY;
    }

    private boolean isDiscardEnvelopes() {
        final Object discard = this.properties.get(PROP_DISCARD_ENVELOPES);
        return discard instanceof Boolean ? (Boolean) discard : false;
    }
}
