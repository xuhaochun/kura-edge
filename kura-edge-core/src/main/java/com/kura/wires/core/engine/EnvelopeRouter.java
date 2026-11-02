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
import java.util.concurrent.Executor;

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
 * 投递在注入的 {@link Executor} 上异步执行；单个组件异常只记录日志，
 * 不中断整图运行（Rules 08 §5）。
 * </p>
 */
final class EnvelopeRouter {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeRouter.class);

    private final WireComponentRegistry registry;
    private final Executor executor;
    private volatile Map<String, List<MultiportWireConfiguration>> routesByEmitter = Map.of();

    /**
     * 创建投递器。
     *
     * @param registry 组件注册表（按接收 PID 查找运行中的组件实例）
     * @param executor 信封投递执行器
     */
    EnvelopeRouter(final WireComponentRegistry registry, final Executor executor) {
        this.registry = registry;
        this.executor = executor;
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
            receiver.ifPresent(component -> this.executor.execute(() -> deliver(component, envelope)));
        }
    }

    private void deliver(final WireComponent receiver, final WireEnvelope envelope) {
        try {
            receiver.onWireReceive(envelope);
            // 缓冲型接收组件（Fifo）只入队：由引擎在投递执行器上异步排空，
            // 组件自身不建消费线程（Rules 08 §5）
            if (receiver instanceof DrainableComponent drainable) {
                this.executor.execute(drainable::drain);
            }
        } catch (final RuntimeException e) {
            log.warn("Component {} failed to handle wire envelope", receiver.getPid(), e);
        }
    }
}
