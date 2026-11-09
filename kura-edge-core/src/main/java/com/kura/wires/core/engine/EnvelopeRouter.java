/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.engine.DrainableComponent;
import com.kura.wires.model.WireEnvelope;

/**
 * 信封投递器：{@link WireGraphEngine} 实现
 * {@link com.kura.wires.core.registry.EnvelopeDispatcher} 语义的包私有协作类。
 * <p>
 * 维护「发射组件 PID → 出边列表」路由表，把发射端经 {@code WireEmitContext}
 * 上交的信封沿图的每条出边投递给对应接收端组件的 {@code onWireReceive}
 * （对照上游 WireAdmin 把 Producer 的发射经 Wire 转交 Consumer 的行为）。
 * 投递交给 {@link DeliveryDispatcher}：有界线程池 + 每接收组件串行 lane（RFC 004），
 * 单个组件异常只记录日志，不中断整图运行（Rules 08 §5）。
 * </p>
 */
final class EnvelopeRouter {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeRouter.class);

    private final WireComponentRegistry registry;
    private final DeliveryDispatcher dispatcher;
    private volatile Map<String, List<MultiportWireConfiguration>> routesByEmitter = Map.of();

    /**
     * 创建投递器。
     *
     * @param registry 组件注册表（按接收 PID 查找运行中的组件实例）
     * @param dispatcher 信封投递器（有界池 + 每接收组件串行 lane）
     */
    EnvelopeRouter(final WireComponentRegistry registry, final DeliveryDispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    /**
     * 按整图连线重建路由表（图更新、删除或实例变更后调用）。
     *
     * @param wires 整图连线
     */
    void refresh(final Collection<MultiportWireConfiguration> wires) {
        final Map<String, List<MultiportWireConfiguration>> routes = new HashMap<>();
        for (final MultiportWireConfiguration wire : wires) {
            routes.computeIfAbsent(wire.getEmitterPid(), key -> new ArrayList<>()).add(wire);
        }
        this.routesByEmitter = Map.copyOf(routes);
    }

    /**
     * 把信封沿 {@code emitterPid} 的所有出边投递给接收端组件。
     *
     * @param emitterPid 发射组件 PID
     * @param envelope 待投递信封
     */
    void dispatch(final String emitterPid, final WireEnvelope envelope) {
        for (final MultiportWireConfiguration wire : this.routesByEmitter.getOrDefault(emitterPid, List.of())) {
            final Optional<WireComponent> receiver = this.registry.getInstance(wire.getReceiverPid());
            receiver.ifPresent(component -> this.dispatcher.submit(component.getPid(),
                    () -> deliver(component, envelope)));
        }
    }

    private void deliver(final WireComponent receiver, final WireEnvelope envelope) {
        try {
            receiver.onWireReceive(envelope);
            // 缓冲型接收组件（Fifo）只入队，排空在这条 lane 内联执行：
            // 既与 receive 保持先后顺序，也不再额外占一个投递线程（Rules 08 §5、RFC 004）
            if (receiver instanceof DrainableComponent drainable) {
                drainable.drain();
            }
        } catch (final RuntimeException e) {
            log.warn("Component {} failed to handle wire envelope", receiver.getPid(), e);
        }
    }
}
