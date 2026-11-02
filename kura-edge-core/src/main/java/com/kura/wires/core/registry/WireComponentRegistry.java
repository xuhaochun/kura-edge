/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kura.wires.engine.SelfConfiguringComponent;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireComponent;

/**
 * Wire 组件注册表：跟踪组件工厂与运行中的组件实例，对照上游
 * {@code WireComponentTrackerCustomizer} + OSGi ServiceTracker 的发现机制，
 * 在本项目里改为构造器注入的全部 {@link WireComponentFactory} Spring Bean。
 * <p>
 * 职责：按 factoryPid 查找工厂、创建/销毁组件实例、维护实例 PID → factoryPid
 * 映射，并向实例注入 {@link WireEmitContext}（发射经 {@link EnvelopeDispatcher}
 * 回到图引擎）。分发器由图引擎在初始化时回注，避免 Bean 循环依赖。
 * </p>
 */
@Component
public class WireComponentRegistry {

    private static final Logger log = LoggerFactory.getLogger(WireComponentRegistry.class);

    private final Map<String, WireComponentFactory> factories = new LinkedHashMap<>();
    private final Map<String, WireComponent> instances = new LinkedHashMap<>();
    private final Map<String, String> instanceFactoryPids = new LinkedHashMap<>();
    private final List<WireComponentRegistryListener> listeners = new CopyOnWriteArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile EnvelopeDispatcher dispatcher = (pid, envelope) -> log
            .warn("Dropping envelope from {} : no graph engine dispatcher bound yet", pid);

    /**
     * 创建注册表，Spring 会注入全部可用的 {@link WireComponentFactory} Bean。
     *
     * @param availableFactories 全部组件工厂（可为 null 或空，此时仅支持手动注册）
     */
    public WireComponentRegistry(final List<WireComponentFactory> availableFactories) {
        for (final WireComponentFactory factory : availableFactories == null ? List.<WireComponentFactory>of()
                : availableFactories) {
            registerFactory(factory);
        }
    }

    /**
     * 注册一个组件工厂（测试与动态扩展用；同名工厂 PID 后注册者覆盖前者）。
     *
     * @param factory 组件工厂
     */
    public void registerFactory(final WireComponentFactory factory) {
        if (factory == null) {
            return;
        }
        this.factories.put(factory.factoryPid(), factory);
    }

    /**
     * 回注信封分发器（由图引擎在初始化时调用）。
     *
     * @param envelopeDispatcher 信封分发回调
     */
    public void setEnvelopeDispatcher(final EnvelopeDispatcher envelopeDispatcher) {
        if (envelopeDispatcher != null) {
            this.dispatcher = envelopeDispatcher;
        }
    }

    /**
     * 返回已注册的全部组件工厂 PID。
     *
     * @return 工厂 PID 集合（只读）
     */
    public Collection<String> getFactoryPids() {
        return Collections.unmodifiableSet(this.factories.keySet());
    }

    /**
     * 按工厂 PID 查找组件工厂。
     *
     * @param factoryPid 工厂 PID
     * @return 组件工厂
     */
    public Optional<WireComponentFactory> findFactory(final String factoryPid) {
        return Optional.ofNullable(this.factories.get(factoryPid));
    }

    /**
     * 注册实例变更监听器（已注册的同一实例忽略）。
     *
     * @param listener 实例变更监听器
     */
    public void addListener(final WireComponentRegistryListener listener) {
        if (listener != null && !this.listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    /**
     * 移除实例变更监听器。
     *
     * @param listener 实例变更监听器
     */
    public void removeListener(final WireComponentRegistryListener listener) {
        if (listener != null) {
            this.listeners.remove(listener);
        }
    }

    /**
     * 创建并跟踪一个组件实例。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 组件工厂 PID
     * @param properties 组件配置属性（已合并默认值）
     * @return 创建的组件实例
     * @throws WiresException 工厂未注册（NOT_FOUND）或工厂创建失败（ENGINE_ERROR）时
     */
    public WireComponent createInstance(final String pid, final String factoryPid, final Map<String, Object> properties) {
        final Optional<WireComponent> existing = reuseCandidate(pid, factoryPid);
        if (existing.isPresent()) {
            return existing.get();
        }
        final WireComponentFactory factory = findFactory(factoryPid).orElseThrow(
                () -> new WiresException(ErrorCode.NOT_FOUND, "No WireComponentFactory registered for factory PID " + factoryPid));
        final WireComponent instance;
        try {
            instance = factory.create(pid, properties, this.emitContextFor(pid));
        } catch (final Exception e) {
            throw new WiresException(ErrorCode.ENGINE_ERROR, "Failed to create component instance " + pid, e);
        }
        this.lock.writeLock().lock();
        try {
            this.instances.put(pid, instance);
            this.instanceFactoryPids.put(pid, factoryPid);
        } finally {
            this.lock.writeLock().unlock();
        }
        log.info("Created wire component instance {} (factory {})", pid, factoryPid);
        notifyCreated(pid, factoryPid, instance);
        return instance;
    }

    /**
     * 复用判定：自配置组件（资产）的实例由配置掌管，图挂载时不应另建第二个。
     * <p>
     * 非自配置组件不做复用——调用方（图引擎）在需要换配置时会先销毁。
     * </p>
     */
    private Optional<WireComponent> reuseCandidate(final String pid, final String factoryPid) {
        this.lock.readLock().lock();
        try {
            final WireComponent existing = this.instances.get(pid);
            if (existing instanceof SelfConfiguringComponent && factoryPid.equals(this.instanceFactoryPids.get(pid))) {
                log.debug("Reusing self-configuring component instance {}", pid);
                return Optional.of(existing);
            }
            return Optional.empty();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * 销毁组件实例（对照上游 DS deactivate / deleteFactoryConfiguration 后的实例回收）。
     *
     * @param pid 组件实例 PID
     * @return 实例存在且已移除时为 true
     */
    public boolean destroyInstance(final String pid) {
        final String factoryPid;
        this.lock.writeLock().lock();
        try {
            factoryPid = this.instanceFactoryPids.remove(pid);
            final boolean removed = this.instances.remove(pid) != null;
            if (!removed) {
                return false;
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        log.info("Destroyed wire component instance {}", pid);
        notifyDestroyed(pid, factoryPid);
        return true;
    }

    /**
     * 按实例 PID 查找运行中的组件。
     *
     * @param pid 组件实例 PID
     * @return 组件实例
     */
    public Optional<WireComponent> getInstance(final String pid) {
        this.lock.readLock().lock();
        try {
            return Optional.ofNullable(this.instances.get(pid));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * 返回全部运行中的组件实例。
     *
     * @return 组件实例集合（只读）
     */
    public Collection<WireComponent> getInstances() {
        this.lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(this.instances.values()));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * 返回实例对应的工厂 PID。
     *
     * @param pid 组件实例 PID
     * @return 工厂 PID
     */
    public Optional<String> getFactoryPidOf(final String pid) {
        this.lock.readLock().lock();
        try {
            return Optional.ofNullable(this.instanceFactoryPids.get(pid));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private WireEmitContext emitContextFor(final String pid) {
        return envelope -> this.dispatcher.dispatch(pid, envelope);
    }

    private void notifyCreated(final String pid, final String factoryPid, final WireComponent instance) {
        for (final WireComponentRegistryListener listener : this.listeners) {
            listener.onInstanceCreated(pid, factoryPid, instance);
        }
    }

    private void notifyDestroyed(final String pid, final String factoryPid) {
        for (final WireComponentRegistryListener listener : this.listeners) {
            listener.onInstanceDestroyed(pid, factoryPid);
        }
    }
}
