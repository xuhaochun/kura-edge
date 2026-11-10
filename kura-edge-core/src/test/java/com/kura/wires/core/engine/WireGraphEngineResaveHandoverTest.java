/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.snapshot.GraphJsonCodec;
import com.kura.wires.core.snapshot.SnapshotStore;
import com.kura.wires.core.testsupport.MutableClock;
import com.kura.wires.core.testsupport.InMemoryComponentConfigurationService;
import com.kura.wires.engine.ScheduledEmitter;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ocd;

/**
 * 再次保存整图时自驱动组件（定时器）的交接行为（Gherkin 风格，注入假调度器，不依赖真实时钟）：
 * 配置变化必须「停旧实例、按新配置启新实例」，且旧实例残留的调度任务不得再 tick、不得续排，
 * 否则保存后旧定时器会按原间隔继续发射，新定时器反而被顶掉。
 */
class WireGraphEngineResaveHandoverTest {

    private static final String SERVICE_PID = WireGraphConstants.WIRE_GRAPH_SERVICE_PID;
    private static final String PID = "timer";
    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";

    private EmitterFactory emitterFactory;
    private RecordingScheduler scheduler;
    private MutableClock clock;
    private WireGraphEngine engine;

    @BeforeEach
    public void setUp() {
        final ComponentConfigurationService configurationService = new InMemoryComponentConfigurationService();
        this.emitterFactory = new EmitterFactory();
        final SnapshotStore snapshotStore = mock(SnapshotStore.class);
        final Map<String, String> snapshots = new HashMap<>();
        when(snapshotStore.load(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(snapshots.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> {
            snapshots.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(snapshotStore).save(anyString(), anyString());
        this.scheduler = new RecordingScheduler();
        this.clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        this.engine = new WireGraphEngine(new WireComponentRegistry(List.of(this.emitterFactory)),
                configurationService, snapshotStore, new GraphJsonCodec(), Runnable::run, this.scheduler, this.clock);
    }

    @Test
    public void successfulResaveRestartsSimpleTimerWithNewInterval() {
        givenSimpleTimerSavedWithInterval(1000L);
        final Runnable stalePeriodicTask = this.scheduler.pendingTask();

        whenGraphResavedWithSimpleInterval(200L);

        thenPendingPeriodIs(200L);
        whenStalePeriodicTaskFires(stalePeriodicTask);
        thenEmitterTicks(0, 0);
        whenPendingTaskFires();
        thenEmitterTicks(0, 1);
    }

    @Test
    public void successfulResaveDiscardsInFlightCronTaskOfReplacedInstance() {
        givenCronTimerSavedWithExpression("0/1 * * * * ?");
        final Runnable staleCronTask = this.scheduler.pendingTask();

        whenGraphResavedWithCronExpression("0/5 * * * * ?");

        whenStaleCronTaskFires(staleCronTask);
        thenEmitterTicks(0, 0);
        whenPendingTaskFires();
        thenEmitterTicks(0, 1);
    }

    @Test
    public void successfulResaveWithoutPropertyChangeKeepsRunningInstance() {
        givenSimpleTimerSavedWithInterval(1000L);
        final WireComponent running = this.emitterFactory.emitters.get(0);

        whenGraphResavedWithSimpleInterval(1000L);

        assertThat(this.emitterFactory.emitters).hasSize(1);
        assertThat(this.emitterFactory.emitters.get(0)).isSameAs(running);
        assertThat(this.scheduler.scheduleCount).isEqualTo(1);
    }

    private void givenSimpleTimerSavedWithInterval(final long intervalMillis) {
        this.engine.update(graph(Map.of("type", "SIMPLE", "interval", intervalMillis)));
    }

    private void givenCronTimerSavedWithExpression(final String cron) {
        this.engine.update(graph(Map.of("type", "CRON", "cron", cron)));
    }

    private void whenGraphResavedWithSimpleInterval(final long intervalMillis) {
        this.engine.update(graph(Map.of("type", "SIMPLE", "interval", intervalMillis)));
    }

    private void whenGraphResavedWithCronExpression(final String cron) {
        this.engine.update(graph(Map.of("type", "CRON", "cron", cron)));
    }

    private void whenStalePeriodicTaskFires(final Runnable stalePeriodicTask) {
        stalePeriodicTask.run();
    }

    private void whenStaleCronTaskFires(final Runnable staleCronTask) {
        this.clock.advanceMillis(this.scheduler.pendingDelay);
        staleCronTask.run();
    }

    private void whenPendingTaskFires() {
        // 假调度器不真的等：把注入时钟推进到排程时刻，模拟「到点才跑」
        this.clock.advanceMillis(this.scheduler.pendingDelay);
        this.scheduler.pendingTask().run();
    }

    private void thenPendingPeriodIs(final long expectedPeriodMillis) {
        assertThat(this.scheduler.pendingPeriod).isEqualTo(expectedPeriodMillis);
        assertThat(this.scheduler.cancelledPids).contains(PID);
    }

    private void thenEmitterTicks(final int oldEmitterTicks, final int newEmitterTicks) {
        assertThat(this.emitterFactory.emitters.get(0).ticks.get()).isEqualTo(oldEmitterTicks);
        assertThat(this.emitterFactory.emitters.get(1).ticks.get()).isEqualTo(newEmitterTicks);
    }

    private WireGraphConfiguration graph(final Map<String, Object> runtimeProperties) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID);
        properties.putAll(runtimeProperties);
        final ComponentConfiguration configuration = new ComponentConfiguration(PID, null, properties);
        return new WireGraphConfiguration(List.of(new WireComponentConfiguration(configuration, Map.of(
                WireGraphConstants.INPUT_PORT_COUNT, 0, WireGraphConstants.OUTPUT_PORT_COUNT, 1))), List.of());
    }

    /** 按配置里的 {@code type} 造 SIMPLE / CRON 两种发射器，并保留每次创建的实例供断言。 */
    static final class EmitterFactory implements WireComponentFactory {

        private final List<StubEmitter> emitters = new ArrayList<>();

        @Override
        public String factoryPid() {
            return TIMER_FACTORY_PID;
        }

        @Override
        public int defaultInputPortCount() {
            return 0;
        }

        @Override
        public int defaultOutputPortCount() {
            return 1;
        }

        @Override
        public Ocd getDefinition() {
            return new Ocd();
        }

        @Override
        public WireComponent create(final String pid, final Map<String, Object> properties,
                final WireEmitContext emitContext) {
            final boolean simple = "SIMPLE".equals(properties.get("type"));
            final StubEmitter emitter = simple
                    ? StubEmitter.periodic(pid, ((Number) properties.get("interval")).longValue())
                    : StubEmitter.cron(pid);
            this.emitters.add(emitter);
            return emitter;
        }
    }

    static final class StubEmitter implements WireComponent, ScheduledEmitter {

        private final String pid;
        private final boolean periodic;
        private final long periodMillis;
        private final AtomicInteger ticks = new AtomicInteger();

        private StubEmitter(final String pid, final boolean periodic, final long periodMillis) {
            this.pid = pid;
            this.periodic = periodic;
            this.periodMillis = periodMillis;
        }

        static StubEmitter periodic(final String pid, final long periodMillis) {
            return new StubEmitter(pid, true, periodMillis);
        }

        static StubEmitter cron(final String pid) {
            return new StubEmitter(pid, false, 0L);
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public void tick() {
            this.ticks.incrementAndGet();
        }

        @Override
        public boolean isPeriodic() {
            return this.periodic;
        }

        @Override
        public long getInitialDelayMillis() {
            return 0L;
        }

        @Override
        public long getPeriodMillis() {
            return this.periodMillis;
        }

        @Override
        public Instant nextCronFireTime(final Instant after) {
            return after.plusSeconds(1L);
        }
    }

    static final class RecordingScheduler implements TickScheduler {

        private final Map<String, Runnable> pending = new LinkedHashMap<>();
        private final List<String> cancelledPids = new ArrayList<>();
        private int scheduleCount;
        private Long pendingPeriod;
        private long pendingDelay;

        @Override
        public void schedulePeriodic(final String pid, final Runnable task, final long initialDelayMillis,
                final long periodMillis) {
            this.scheduleCount++;
            this.pendingPeriod = periodMillis;
            this.pendingDelay = initialDelayMillis;
            this.pending.put(pid, task);
        }

        @Override
        public void scheduleOnce(final String pid, final Runnable task, final long delayMillis) {
            this.scheduleCount++;
            this.pendingPeriod = null;
            this.pendingDelay = delayMillis;
            this.pending.put(pid, task);
        }

        @Override
        public void cancel(final String pid) {
            this.cancelledPids.add(pid);
            this.pending.remove(pid);
        }

        @Override
        public void cancelAll() {
            this.pending.clear();
        }

        @Override
        public void shutdown() {
            this.pending.clear();
        }

        Runnable pendingTask() {
            return this.pending.get(PID);
        }
    }
}
