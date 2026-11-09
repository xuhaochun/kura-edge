/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.registry.WireComponentRegistryListener;
import com.kura.wires.core.snapshot.GraphJsonCodec;
import com.kura.wires.core.snapshot.SnapshotStore;
import com.kura.wires.engine.ScheduledEmitter;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireGraphService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;

/**
 * 图引擎：{@link WireGraphService} 的 Spring 实现，替换上游依赖 OSGi
 * WireAdmin 与 ConfigurationAdmin 的 {@code WireGraphServiceImpl}。
 * <p>
 * 职责（对照上游 update 流程）：
 * <ol>
 * <li>整体校验：图结构合法→ {@link CycleDetector#hasCycle}（TOPOLOGY_CYCLE）→
 * {@link PortValidator}（PORT_MISMATCH）；</li>
 * <li>与快照中旧图 diff：新增组件创建实例与工厂配置、删除组件销毁实例与配置、
 * 保留组件刷新配置；</li>
 * <li>把整图经 {@link GraphJsonCodec#marshal} 后写入 {@link SnapshotStore}
 * （servicePid = {@link WireGraphConstants#WIRE_GRAPH_SERVICE_PID}，字段名与上游
 * {@code WireGraph} 属性一致）。</li>
 * </ol>
 * 变更阶段失败时尽量回滚已执行步骤并在异常消息中聚合（对照上游
 * FailureHandler 思路），不留下半新半旧的图。引擎同时实现
 * {@link com.kura.wires.core.registry.EnvelopeDispatcher} 语义（经 {@link EnvelopeRouter}），构造后回注到组件注册表。
 * </p>
 */
@Service
public class WireGraphEngine implements WireGraphService {

    /** 组件配置中的发射端口数属性名（对照上游 Constants.EMITTER_PORT_COUNT_PROP_NAME） */
    static final String EMITTER_PORT_COUNT_PROP = "wire.emitter.port.count";

    /** 组件配置中的接收端口数属性名（对照上游 Constants.RECEIVER_PORT_COUNT_PROP_NAME） */
    static final String RECEIVER_PORT_COUNT_PROP = "wire.receiver.port.count";

    private static final Logger log = LoggerFactory.getLogger(WireGraphEngine.class);

    /** CRON tick 允许在调度线程上原地等待的最长时间（超过则重新排程，避免占住共享调度线程） */
    private static final long MAX_IN_PLACE_WAIT_NANOS = 2_000_000L;

    private final WireComponentRegistry registry;
    private final ComponentConfigurationService configurationService;
    private final SnapshotStore snapshotStore;
    private final GraphJsonCodec codec;
    private final PortValidator portValidator;
    private final EnvelopeRouter router;
    /** 信封投递器：有界池 + 每接收组件串行 lane（RFC 004） */
    private final DeliveryDispatcher deliveryDispatcher;

    /** 时钟（续排与「不早于计划时刻」判定用；测试注入可推进的时钟，避免真实等待） */
    private final Clock clock;

    /** 自驱动组件的 tick 调度器（Timer 等） */
    private final TickScheduler tickScheduler;
    /**
     * 实例世代号（pid → 代数）：实例创建/销毁时递增，调度任务带上创建时的代数，
     * 代数过期即视为「上一代实例的任务」，不再 tick、也不再续排。
     * <p>
     * 必须有时代号：CRON 链在每次 tick 的 finally 里排下一次，若保存恰好发生在某次
     * tick 执行期间，旧实例的这条任务会在 {@code cancel} 之后仍把「旧实例」重新排上，
     * 于是新的定时器被顶掉、旧定时器继续按原配置跑——表现就是「改了保存后旧的还在运行」。
     * </p>
     */
    private final Map<String, Long> instanceGenerations = new ConcurrentHashMap<>();
    private volatile WireGraphConfiguration currentGraph = new WireGraphConfiguration();

    /**
     * 创建图引擎（Spring 构造器注入，使用默认异步投递线程池）。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param snapshotStore 整图快照存储
     * @param codec 整图 JSON 编解码器
     */
    @Autowired
    public WireGraphEngine(final WireComponentRegistry registry, final ComponentConfigurationService configurationService,
            final SnapshotStore snapshotStore, final GraphJsonCodec codec) {
        this(registry, configurationService, snapshotStore, codec, DeliverySettings.fromSystemProperties());
    }

    /**
     * 创建图引擎（由投递参数配置投递线程池与排队上限）。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param snapshotStore 整图快照存储
     * @param codec 整图 JSON 编解码器
     * @param deliverySettings 投递参数
     */
    private WireGraphEngine(final WireComponentRegistry registry,
            final ComponentConfigurationService configurationService, final SnapshotStore snapshotStore,
            final GraphJsonCodec codec, final DeliverySettings deliverySettings) {
        this(registry, configurationService, snapshotStore, codec, newDeliveryExecutor(deliverySettings),
                new ScheduledExecutorTickScheduler(), Clock.systemUTC(), deliverySettings);
    }

    /**
     * 创建图引擎（测试可注入同步执行器）。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param snapshotStore 整图快照存储
     * @param codec 整图 JSON 编解码器
     * @param deliveryExecutor 信封投递执行器
     */
    WireGraphEngine(final WireComponentRegistry registry, final ComponentConfigurationService configurationService,
            final SnapshotStore snapshotStore, final GraphJsonCodec codec, final Executor deliveryExecutor) {
        this(registry, configurationService, snapshotStore, codec, deliveryExecutor,
                new ScheduledExecutorTickScheduler());
    }

    /**
     * 创建图引擎（测试可注入调度器，用于确定性验证 tick 排程）。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param snapshotStore 整图快照存储
     * @param codec 整图 JSON 编解码器
     * @param deliveryExecutor 信封投递执行器
     * @param tickScheduler 自驱动组件的 tick 调度器
     */
    WireGraphEngine(final WireComponentRegistry registry, final ComponentConfigurationService configurationService,
            final SnapshotStore snapshotStore, final GraphJsonCodec codec, final Executor deliveryExecutor,
            final TickScheduler tickScheduler) {
        this(registry, configurationService, snapshotStore, codec, deliveryExecutor, tickScheduler, Clock.systemUTC(),
                DeliverySettings.defaults());
    }

    /**
     * 创建图引擎（测试可注入调度器与可推进时钟，确定性验证 CRON 排程）。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param snapshotStore 整图快照存储
     * @param codec 整图 JSON 编解码器
     * @param deliveryExecutor 信封投递执行器
     * @param tickScheduler 自驱动组件的 tick 调度器
     * @param clock 时钟
     */
    WireGraphEngine(final WireComponentRegistry registry, final ComponentConfigurationService configurationService,
            final SnapshotStore snapshotStore, final GraphJsonCodec codec, final Executor deliveryExecutor,
            final TickScheduler tickScheduler, final Clock clock) {
        this(registry, configurationService, snapshotStore, codec, deliveryExecutor, tickScheduler, clock,
                DeliverySettings.defaults());
    }

    /**
     * 创建图引擎（测试可注入执行器、调度器、时钟与投递参数）。
     *
     * @param registry 组件注册表
     * @param configurationService 组件配置服务
     * @param snapshotStore 整图快照存储
     * @param codec 整图 JSON 编解码器
     * @param deliveryExecutor 信封投递执行器
     * @param tickScheduler 自驱动组件的 tick 调度器
     * @param clock 时钟
     * @param deliverySettings 投递参数（每个接收组件的排队上限等）
     */
    WireGraphEngine(final WireComponentRegistry registry, final ComponentConfigurationService configurationService,
            final SnapshotStore snapshotStore, final GraphJsonCodec codec, final Executor deliveryExecutor,
            final TickScheduler tickScheduler, final Clock clock, final DeliverySettings deliverySettings) {
        this.registry = registry;
        this.configurationService = configurationService;
        this.snapshotStore = snapshotStore;
        this.codec = codec;
        this.tickScheduler = tickScheduler;
        this.clock = clock;
        this.portValidator = new PortValidator(registry);
        this.deliveryDispatcher = new DeliveryDispatcher(deliveryExecutor, deliverySettings.receiverQueueCapacity());
        this.router = new EnvelopeRouter(registry, this.deliveryDispatcher);
        registry.setEnvelopeDispatcher(this::dispatch);
        registry.addListener(new WireComponentRegistryListener() {

            @Override
            public void onInstanceCreated(final String pid, final String factoryPid, final WireComponent instance) {
                scheduleInstance(pid, instance, nextGeneration(pid));
                refreshRoutes();
            }

            @Override
            public void onInstanceDestroyed(final String pid, final String factoryPid) {
                // 先推进代数再取消排程：正在执行的旧任务看到代数已变，就不会再续排自己
                nextGeneration(pid);
                WireGraphEngine.this.tickScheduler.cancel(pid);
                // 该实例已销毁：丢掉它排队中的信封，避免投递给已销毁的旧实例（重建替换时也会走到这里）
                WireGraphEngine.this.deliveryDispatcher.removeLane(pid);
                refreshRoutes();
            }
        });
        loadGraph();
        log.info("Wire graph engine started");
    }

    /**
     * 返回信封投递计数快照（投递量 / 丢弃量），用于观测慢组件造成的积压与丢信封情况。
     *
     * @return 投递计数快照
     */
    public DeliveryStats getDeliveryStats() {
        return this.deliveryDispatcher.stats();
    }

    @Override
    public synchronized WireGraphConfiguration get() {
        return loadGraph();
    }

    @Override
    public synchronized void update(final WireGraphConfiguration graphConfiguration) {
        validate(graphConfiguration);
        final WireGraphConfiguration oldGraph = loadGraph();
        final Map<String, WireComponentConfiguration> oldByPid = componentsByPid(oldGraph);
        final Map<String, WireComponentConfiguration> newByPid = componentsByPid(graphConfiguration);

        final List<WireComponentConfiguration> toCreate = new ArrayList<>();
        final List<WireComponentConfiguration> retained = new ArrayList<>();
        for (final WireComponentConfiguration component : graphConfiguration.getWireComponentConfigurations()) {
            final String pid = component.getConfiguration().getPid();
            (oldByPid.containsKey(pid) ? retained : toCreate).add(component);
        }
        final List<WireComponentConfiguration> toDelete = oldGraph.getWireComponentConfigurations().stream()
                .filter(component -> !newByPid.containsKey(component.getConfiguration().getPid())).toList();
        validateFactories(toCreate);

        apply(graphConfiguration, toCreate, retained, toDelete, oldByPid);
        log.info("Wire graph updated: {} component(s), {} wire(s)",
                graphConfiguration.getWireComponentConfigurations().size(), graphConfiguration.getWireConfigurations()
                        .size());
    }

    @Override
    public synchronized void delete() {
        final WireGraphConfiguration graph = loadGraph();
        final List<String> errors = new ArrayList<>();
        for (final WireComponentConfiguration component : graph.getWireComponentConfigurations()) {
            final String pid = component.getConfiguration().getPid();
            try {
                this.registry.destroyInstance(pid);
                this.configurationService.deleteFactoryConfiguration(pid);
            } catch (final RuntimeException e) {
                errors.add("failed to remove component " + pid + " : " + e.getMessage());
            }
        }
        try {
            this.snapshotStore.delete(WireGraphConstants.WIRE_GRAPH_SERVICE_PID);
        } catch (final RuntimeException e) {
            errors.add("failed to delete graph snapshot : " + e.getMessage());
        }
        updateGraphCache(new WireGraphConfiguration());
        refreshRoutes();
        this.tickScheduler.cancelAll();
        this.instanceGenerations.clear();
        this.deliveryDispatcher.removeAllLanes();
        log.info("Wire graph deleted");
        if (!errors.isEmpty()) {
            throw new WiresException(ErrorCode.ENGINE_ERROR,
                    "Wire graph deletion completed with failures: " + String.join("; ", errors));
        }
    }

    /**
     * 停止信封投递线程池（Spring 生命周期回调）。
     */
    @PreDestroy
    public void shutdown() {
        this.tickScheduler.cancelAll();
        this.tickScheduler.shutdown();
        this.deliveryDispatcher.close();
    }

    private void validate(final WireGraphConfiguration graphConfiguration) {
        if (graphConfiguration == null) {
            throw new WiresException(ErrorCode.CONFIG_INVALID, "Wire graph configuration must not be null");
        }
        for (final WireComponentConfiguration component : graphConfiguration.getWireComponentConfigurations()) {
            if (component == null || component.getConfiguration() == null
                    || component.getConfiguration().getPid() == null) {
                throw new WiresException(ErrorCode.CONFIG_INVALID, "Every wire component must have a PID");
            }
        }
        if (CycleDetector.hasCycle(graphConfiguration.getWireConfigurations())) {
            throw new WiresException(ErrorCode.TOPOLOGY_CYCLE, "Wire graph contains a cycle");
        }
        this.portValidator.validate(graphConfiguration);
    }

    private void validateFactories(final List<WireComponentConfiguration> toCreate) {
        for (final WireComponentConfiguration component : toCreate) {
            final String pid = component.getConfiguration().getPid();
            if (findFactoryPid(component).isEmpty()) {
                throw new WiresException(ErrorCode.CONFIG_INVALID,
                        "Component " + pid + " must specify a valid " + WireGraphConstants.SERVICE_FACTORY_PID);
            }
        }
    }

    private void apply(final WireGraphConfiguration newGraph, final List<WireComponentConfiguration> toCreate,
            final List<WireComponentConfiguration> retained, final List<WireComponentConfiguration> toDelete,
            final Map<String, WireComponentConfiguration> oldByPid) {
        final List<String> errors = new ArrayList<>();
        final Map<String, String> createdInstances = new LinkedHashMap<>();
        final Map<String, String> createdConfigurations = new LinkedHashMap<>();
        final Map<String, RemovedComponent> removedComponents = new LinkedHashMap<>();
        final Map<String, RemovedConfiguration> removedConfigurations = new LinkedHashMap<>();
        try {
            for (final WireComponentConfiguration component : toCreate) {
                final String pid = component.getConfiguration().getPid();
                final String factoryPid = findFactoryPid(component).orElseThrow();
                this.registry.createInstance(pid, factoryPid, instanceProperties(component, factoryPid));
                createdInstances.put(pid, factoryPid);
            }
            for (final WireComponentConfiguration component : toDelete) {
                final String pid = component.getConfiguration().getPid();
                final String factoryPid = findFactoryPid(component).orElse(null);
                removedComponents.put(pid, new RemovedComponent(factoryPid,
                        instanceProperties(component, factoryPid)));
                this.registry.destroyInstance(pid);
            }
            for (final WireComponentConfiguration component : retained) {
                final String pid = component.getConfiguration().getPid();
                final WireComponentConfiguration previous = oldByPid.get(pid);
                if (propertiesChanged(previous, component)) {
                    // 属性变化必须重建实例：Timer 等组件的配置在构造期固化，
                    // 只更新库不重建会让「改间隔」在运行期不生效
                    rebuildInstance(pid, previous, component, removedComponents, createdInstances);
                }
                this.configurationService.updateConfiguration(pid,
                        component.getConfiguration().getProperties());
            }
            for (final WireComponentConfiguration component : toCreate) {
                final String pid = component.getConfiguration().getPid();
                final String factoryPid = createdInstances.get(pid);
                if (this.configurationService.exists(pid)) {
                    // 自配置组件（资产）通常已由 Drivers and Assets 页面建好配置：
                    // 图里引用同一 pid 时复用既有配置，不再建第二条（否则会报 already exists）
                    log.info("Reusing existing configuration for {} while adding it to the wire graph", pid);
                    continue;
                }
                this.configurationService.createFactoryConfiguration(factoryPid, pid,
                        configurationProperties(component, factoryPid));
                createdConfigurations.put(pid, factoryPid);
            }
            for (final WireComponentConfiguration component : toDelete) {
                final String pid = component.getConfiguration().getPid();
                removedConfigurations.put(pid, new RemovedConfiguration(createdConfigurationFactoryPid(component),
                        component.getConfiguration().getProperties()));
                this.configurationService.deleteFactoryConfiguration(pid);
            }
        } catch (final RuntimeException e) {
            errors.add(e.getMessage());
            rollback(createdInstances, removedComponents, createdConfigurations, removedConfigurations, errors);
            throw new WiresException(ErrorCode.ENGINE_ERROR,
                    "Wire graph update failed and was rolled back: " + String.join("; ", errors), e);
        }
        this.snapshotStore.save(WireGraphConstants.WIRE_GRAPH_SERVICE_PID, this.codec.marshal(newGraph));
        updateGraphCache(newGraph);
        // 路由表基于缓存的图，必须在缓存更新后重建：首次保存时实例先于图缓存创建，
        // 监听器回调那一刻拿到的是旧图（空），不在这里补一次会导致投递永远没有出边
        refreshRoutes();
    }

    private String createdConfigurationFactoryPid(final WireComponentConfiguration component) {
        return findFactoryPid(component).orElse(null);
    }

    private void rollback(final Map<String, String> createdInstances,
            final Map<String, RemovedComponent> removedComponents,
            final Map<String, String> createdConfigurations,
            final Map<String, RemovedConfiguration> removedConfigurations, final List<String> errors) {
        for (final String pid : createdConfigurations.keySet()) {
            try {
                this.configurationService.deleteFactoryConfiguration(pid);
            } catch (final RuntimeException e) {
                errors.add("rollback: failed to delete configuration " + pid + " : " + e.getMessage());
            }
        }
        for (final Map.Entry<String, RemovedConfiguration> entry : removedConfigurations.entrySet()) {
            if (entry.getValue().factoryPid() == null) {
                continue;
            }
            try {
                this.configurationService.createFactoryConfiguration(entry.getValue().factoryPid(), entry.getKey(),
                        entry.getValue().properties());
            } catch (final RuntimeException e) {
                errors.add("rollback: failed to restore configuration " + entry.getKey() + " : " + e.getMessage());
            }
        }
        for (final String pid : createdInstances.keySet()) {
            try {
                this.registry.destroyInstance(pid);
            } catch (final RuntimeException e) {
                errors.add("rollback: failed to destroy instance " + pid + " : " + e.getMessage());
            }
        }
        for (final Map.Entry<String, RemovedComponent> entry : removedComponents.entrySet()) {
            if (entry.getValue().factoryPid() == null) {
                continue;
            }
            try {
                this.registry.createInstance(entry.getKey(), entry.getValue().factoryPid(),
                        entry.getValue().properties());
            } catch (final RuntimeException e) {
                errors.add("rollback: failed to restore instance " + entry.getKey() + " : " + e.getMessage());
            }
        }
    }

    private WireGraphConfiguration loadGraph() {
        final Optional<String> json = this.snapshotStore.load(WireGraphConstants.WIRE_GRAPH_SERVICE_PID);
        if (json.isEmpty()) {
            updateGraphCache(new WireGraphConfiguration());
            return this.currentGraph;
        }
        final WireGraphConfiguration graph = withComponentConfigurations(this.codec.unmarshal(json.get()));
        updateGraphCache(graph);
        return graph;
    }

    /**
     * 回填图内组件的配置属性：图 JSON 与上游一致，只携带拓扑与渲染属性，
     * 组件配置属性（含 {@code service.factoryPid}）以配置服务为准。
     * 对照上游 {@code WireGraphServiceImpl#get} 用 ConfigurationAdmin 补全
     * {@code ComponentConfiguration} 的行为；缺配置的组件按图 JSON 原样保留。
     *
     * @param graph 从快照反序列化出的图
     * @return 组件配置已回填的图
     */
    private WireGraphConfiguration withComponentConfigurations(final WireGraphConfiguration graph) {
        final List<WireComponentConfiguration> hydrated = new ArrayList<>();
        for (final WireComponentConfiguration component : graph.getWireComponentConfigurations()) {
            final String pid = component.getConfiguration().getPid();
            final Optional<ComponentConfiguration> stored = this.configurationService.getConfiguration(pid);
            hydrated.add(stored
                    .map(configuration -> new WireComponentConfiguration(configuration, component.getProperties()))
                    .orElse(component));
        }
        return new WireGraphConfiguration(hydrated, graph.getWireConfigurations());
    }

    private void updateGraphCache(final WireGraphConfiguration graph) {
        this.currentGraph = graph;
        refreshRoutes();
    }

    private void refreshRoutes() {
        this.router.refresh(this.currentGraph.getWireConfigurations());
    }

    private void dispatch(final String emitterPid, final WireEnvelope envelope) {
        this.router.dispatch(emitterPid, envelope);
    }

    private static Map<String, WireComponentConfiguration> componentsByPid(final WireGraphConfiguration graph) {
        final Map<String, WireComponentConfiguration> byPid = new LinkedHashMap<>();
        for (final WireComponentConfiguration component : graph.getWireComponentConfigurations()) {
            byPid.put(component.getConfiguration().getPid(), component);
        }
        return byPid;
    }

    private Optional<String> findFactoryPid(final WireComponentConfiguration component) {
        final Object configured = component.getConfiguration().getProperties()
                .get(WireGraphConstants.SERVICE_FACTORY_PID);
        if (configured instanceof final String factoryPid) {
            return Optional.of(factoryPid);
        }
        return this.registry.getFactoryPidOf(component.getConfiguration().getPid());
    }

    private Map<String, Object> mergedComponentProperties(final WireComponentConfiguration component,
            final String factoryPid) {
        final Map<String, Object> merged = new HashMap<>(component.getProperties());
        final WireComponentFactory factory = factoryPid == null ? null
                : this.registry.findFactory(factoryPid).orElse(null);
        if (factory != null) {
            merged.putIfAbsent(WireGraphConstants.INPUT_PORT_COUNT, factory.defaultInputPortCount());
            merged.putIfAbsent(WireGraphConstants.OUTPUT_PORT_COUNT, factory.defaultOutputPortCount());
        }
        return merged;
    }

    private Map<String, Object> configurationProperties(final WireComponentConfiguration component,
            final String factoryPid) {
        final Map<String, Object> properties = new HashMap<>(component.getConfiguration().getProperties());
        properties.putIfAbsent(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid);
        final Map<String, Object> merged = mergedComponentProperties(component, factoryPid);
        properties.put(RECEIVER_PORT_COUNT_PROP, merged.get(WireGraphConstants.INPUT_PORT_COUNT));
        properties.put(EMITTER_PORT_COUNT_PROP, merged.get(WireGraphConstants.OUTPUT_PORT_COUNT));
        return properties;
    }

    /**
     * 为自驱动组件挂调度：SIMPLE 走固定速率，CRON 逐次排程（每次 tick 后算下一次）。
     *
     * @param pid 组件实例 PID
     * @param instance 组件实例
     * @param generation 实例世代号（调度任务只在这一代实例仍有效时执行/续排）
     */
    private void scheduleInstance(final String pid, final WireComponent instance, final long generation) {
        if (!(instance instanceof ScheduledEmitter emitter)) {
            return;
        }
        try {
            if (emitter.isPeriodic()) {
                this.tickScheduler.schedulePeriodic(pid, () -> tickIfCurrent(pid, emitter, generation),
                        emitter.getInitialDelayMillis(), emitter.getPeriodMillis());
            } else {
                scheduleNextCronTick(pid, emitter, generation);
            }
            log.info("Scheduled wire component {} for periodic ticks: {}", pid, emitter.isPeriodic());
        } catch (final RuntimeException e) {
            // 配置非法（如 CRON 表达式错误）不应阻断整图更新，只停掉该组件的 tick
            log.error("Failed to schedule wire component {}: {}", pid, e.getMessage(), e);
        }
    }

    private void scheduleNextCronTick(final String pid, final ScheduledEmitter emitter, final long generation) {
        scheduleCronTickAfter(pid, emitter, generation, this.clock.instant());
    }

    /**
     * 按 {@code after} 之后的第一个 CRON 触发时刻排下一次 tick。
     *
     * @param pid 组件实例 PID
     * @param emitter 自驱动组件
     * @param generation 实例世代号
     * @param after 起算时刻（续排时必须传「上一个计划触发时刻」，原因见 {@link #runCronTick}）
     */
    private void scheduleCronTickAfter(final String pid, final ScheduledEmitter emitter, final long generation,
            final Instant after) {
        if (!isCurrentGeneration(pid, generation)) {
            // 实例已被替换：旧实例的 CRON 链到此为止，不再排下一次
            log.debug("Stopping CRON chain of replaced instance {} (generation {})", pid, generation);
            return;
        }
        final Instant next = emitter.nextCronFireTime(after);
        if (next == null) {
            log.warn("Wire component {} has no further CRON fire time, scheduling stopped", pid);
            return;
        }
        // 延迟向上取整：秒级 CRON 的计划触发时刻是整秒，向下截断会让任务提前不到 1ms 唤醒，
        // 日志时间戳就变成 “:49.999Z” 这种非整秒形态（并且会重算回同一拍，见 runCronTick）
        this.tickScheduler.scheduleOnce(pid, () -> runCronTick(pid, emitter, generation, next),
                delayMillisCeil(this.clock.instant(), next));
    }

    /**
     * 执行一次 CRON tick 并排下一次，下一次从「本次计划触发时刻 {@code fireAt}」起算。
     * <p>
     * 两个约束：
     * </p>
     * <ul>
     * <li>不得早于 {@code fireAt} 发射：秒级 CRON 落在整秒，提前 1ms 唤醒会让日志显示
     * “:49.999Z” 而不是 “:49.000Z”；提前唤醒时补排剩余时间而不是直接 tick。</li>
     * <li>不能用 {@code Instant.now()} 续排：提前唤醒时 {@code CronExpression.next(now)}
     * 仍算回同一个触发时刻，delay 归零会立刻再发一次（同一拍重复日志）。</li>
     * </ul>
     *
     * @param pid 组件实例 PID
     * @param emitter 自驱动组件
     * @param generation 实例世代号
     * @param fireAt 本次 tick 的计划触发时刻
     */
    private void runCronTick(final String pid, final ScheduledEmitter emitter, final long generation,
            final Instant fireAt) {
        if (!awaitFireTime(pid, emitter, generation, fireAt)) {
            return;
        }
        try {
            tickIfCurrent(pid, emitter, generation);
        } finally {
            // 单次触发失败也要排下一次，否则 CRON 链一次异常就断掉；
            // 续排同样按世代号判定，避免把已被替换的旧实例重新排上
            final Instant after = this.clock.instant();
            scheduleCronTickAfter(pid, emitter, generation, after.isAfter(fireAt) ? after : fireAt);
        }
    }

    /**
     * 等到计划触发时刻再发射（秒级 CRON 的计划时刻是整秒）。
     * <p>
     * 调度器按毫秒排程，可能提前不到 1ms 唤醒；直接发射会让时间戳落在 “:49.999”。
     * 亚毫秒级的剩余时间就地 {@link LockSupport#parkNanos} 等过去（唤醒抖动只有几十微秒，
     * 时间戳仍是 “:49.000”）；剩余时间较长时说明调度器唤醒早得不正常，改走重新排程，
     * 不占住共享的调度线程空等。
     * </p>
     *
     * @param pid 组件实例 PID
     * @param emitter 自驱动组件
     * @param generation 实例世代号
     * @param fireAt 本次 tick 的计划触发时刻
     * @return {@code true} 表示已到计划时刻可以发射；{@code false} 表示已重新排程、本次不发射
     */
    private boolean awaitFireTime(final String pid, final ScheduledEmitter emitter, final long generation,
            final Instant fireAt) {
        long remainingNanos = Duration.between(this.clock.instant(), fireAt).toNanos();
        if (remainingNanos <= 0L) {
            return true;
        }
        if (remainingNanos > MAX_IN_PLACE_WAIT_NANOS) {
            this.tickScheduler.scheduleOnce(pid, () -> runCronTick(pid, emitter, generation, fireAt),
                    delayMillisCeil(this.clock.instant(), fireAt));
            return false;
        }
        while (remainingNanos > 0L) {
            LockSupport.parkNanos(remainingNanos);
            remainingNanos = Duration.between(this.clock.instant(), fireAt).toNanos();
        }
        return true;
    }

    /**
     * 从 {@code now} 到 {@code target} 的毫秒延迟，向上取整（保证唤醒时刻不早于 target）。
     *
     * @param now 当前时刻
     * @param target 目标时刻
     * @return 毫秒延迟（已到或已过目标时刻时为 0）
     */
    private static long delayMillisCeil(final Instant now, final Instant target) {
        final long nanos = Duration.between(now, target).toNanos();
        return nanos <= 0L ? 0L : (nanos + 999_999L) / 1_000_000L;
    }

    private void tickIfCurrent(final String pid, final ScheduledEmitter emitter, final long generation) {
        if (isCurrentGeneration(pid, generation)) {
            emitter.tick();
        }
    }

    /**
     * 推进并返回 pid 的实例世代号（创建、销毁各推进一次）。
     *
     * @param pid 组件实例 PID
     * @return 推进后的世代号
     */
    private long nextGeneration(final String pid) {
        return this.instanceGenerations.merge(pid, 1L, Long::sum);
    }

    private boolean isCurrentGeneration(final String pid, final long generation) {
        return this.instanceGenerations.getOrDefault(pid, 0L) == generation;
    }

    private boolean propertiesChanged(final WireComponentConfiguration previous,
            final WireComponentConfiguration current) {
        if (previous == null) {
            return true;
        }
        final String factoryPid = findFactoryPid(current).orElse(null);
        return !Objects.equals(runtimeProperties(instanceProperties(previous, factoryPid)),
                runtimeProperties(instanceProperties(current, factoryPid)));
    }

    /**
     * 去掉只影响画布渲染的属性（坐标、端口名）：这些变化由快照承载，
     * 不需要重建组件实例（否则拖动节点就会重启定时器、清空 FIFO）。
     *
     * @param properties 实例属性
     * @return 仅剩运行期相关属性
     */
    private static Map<String, Object> runtimeProperties(final Map<String, Object> properties) {
        final Map<String, Object> runtime = new HashMap<>(properties);
        runtime.keySet().removeIf(key -> WireGraphConstants.POSITION_X.equals(key)
                || WireGraphConstants.POSITION_Y.equals(key) || key.startsWith("inputPortNames")
                || key.startsWith("outputPortNames") || key.equals(RECEIVER_PORT_COUNT_PROP)
                || key.equals(EMITTER_PORT_COUNT_PROP));
        return runtime;
    }

    /**
     * 组件实例属性：上游把「组件配置」与「渲染属性/端口数」都放在组件的配置里，
     * 因此实例既要拿到运行期配置（Topic、间隔、正则等），也要拿到端口数与坐标。
     * <p>
     * 修复前的实现只传了图 JSON 的渲染属性，组件读不到自己的配置（Timer 会退化成
     * 空 CRON 表达式、RegexFilter 退化成透传），这也是「示例跑不起来」的根因之一。
     * </p>
     *
     * @param component 图内组件配置
     * @param factoryPid 组件工厂 PID
     * @return 实例属性
     */
    private Map<String, Object> instanceProperties(final WireComponentConfiguration component,
            final String factoryPid) {
        // mergedComponentProperties：图 JSON 的渲染属性 + 端口数（含工厂默认值回落）
        final Map<String, Object> properties = new HashMap<>(mergedComponentProperties(component, factoryPid));
        properties.putAll(configurationProperties(component, factoryPid));
        return properties;
    }

    private void rebuildInstance(final String pid, final WireComponentConfiguration previous,
            final WireComponentConfiguration current, final Map<String, RemovedComponent> removedComponents,
            final Map<String, String> createdInstances) {
        final String factoryPid = findFactoryPid(current)
                .orElseGet(() -> previous == null ? null : findFactoryPid(previous).orElse(null));
        removedComponents.put(pid, new RemovedComponent(factoryPid, instanceProperties(previous, factoryPid)));
        this.registry.destroyInstance(pid);
        this.registry.createInstance(pid, factoryPid, instanceProperties(current, factoryPid));
        createdInstances.put(pid, factoryPid);
    }

    /**
     * 创建有界投递线程池（RFC 004）：固定线程数 + 有界共享队列，
     * 池/队列满时抛 {@link java.util.concurrent.RejectedExecutionException} 由
     * {@link DeliveryDispatcher} 计数并丢弃该信封，不再像 cached pool 那样无界建线程。
     *
     * @param settings 投递参数
     * @return 有界投递线程池
     */
    private static ExecutorService newDeliveryExecutor(final DeliverySettings settings) {
        return new ThreadPoolExecutor(settings.threads(), settings.threads(), 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.poolQueueCapacity()), runnable -> {
                    final Thread thread = new Thread(runnable, "wire-envelope-delivery");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 回滚用：被销毁的组件实例快照。 */
    private record RemovedComponent(String factoryPid, Map<String, Object> properties) {
    }

    /** 回滚用：被删除的组件配置快照。 */
    private record RemovedConfiguration(String factoryPid, Map<String, Object> properties) {
    }
}
