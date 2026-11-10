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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.snapshot.GraphJsonCodec;
import com.kura.wires.core.snapshot.SnapshotStore;
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
 * CRON 定时器的发射节奏（真实调度器 + 量化后的假 CRON 时刻，不依赖实际 cron 表达式）：
 * 一拍只允许发射一次。调度器按毫秒延迟排程、可能提前不到 1ms 唤醒，
 * 续排若按 {@code Instant.now()} 起算会算回同一拍并立刻再发一次，表现为下游收到重复日志。
 */
class WireGraphEngineCronCadenceTest {

    private static final String SERVICE_PID = WireGraphConstants.WIRE_GRAPH_SERVICE_PID;
    private static final String PID = "timer";
    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.QuantizedCron";
    private static final long QUANTUM_MILLIS = 100L;
    private static final long OBSERVE_MILLIS = 1200L;

    /** 调度线程写、测试线程读，用并发容器避免数据竞争 */
    private final List<Instant> ticks = new CopyOnWriteArrayList<>();

    private WireGraphEngine engine;

    @AfterEach
    public void tearDown() {
        if (this.engine != null) {
            this.engine.shutdown();
        }
    }

    @Test
    public void successfulCronChainEmitsExactlyOncePerScheduledInstant() throws Exception {
        givenEngineWithQuantizedCronTimer();

        whenWaitingOnePointTwoSeconds();

        thenEveryScheduledInstantFiredOnce();
    }

    private void whenWaitingOnePointTwoSeconds() throws Exception {
        Thread.sleep(OBSERVE_MILLIS);
    }

    private void thenEveryScheduledInstantFiredOnce() {
        assertThat(this.ticks).hasSizeGreaterThanOrEqualTo(4);
        for (int index = 1; index < this.ticks.size(); index++) {
            final long gapMillis = this.ticks.get(index).toEpochMilli() - this.ticks.get(index - 1).toEpochMilli();
            assertThat(gapMillis).as("相邻两次 tick 的间隔（不大于半拍视为同一拍重复发射）").isGreaterThanOrEqualTo(50L);
        }
        for (final Instant tick : this.ticks) {
            final long offsetInQuantum = tick.toEpochMilli() % QUANTUM_MILLIS;
            assertThat(offsetInQuantum)
                    .as("tick 必须落在计划时刻（整拍）上，早于计划时刻会出现 99ms 这类偏移：实际 %s", tick)
                    .isLessThan(QUANTUM_MILLIS / 2);
        }
    }

    private void givenEngineWithQuantizedCronTimer() {
        final ComponentConfigurationService configurationService = new InMemoryComponentConfigurationService();
        final WireComponentRegistry registry = new WireComponentRegistry(List.of(new QuantizedCronFactory()));
        final SnapshotStore snapshotStore = mock(SnapshotStore.class);
        final Map<String, String> snapshots = new HashMap<>();
        when(snapshotStore.load(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(snapshots.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> {
            snapshots.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(snapshotStore).save(anyString(), anyString());
        this.engine = new WireGraphEngine(registry, configurationService, snapshotStore,
                new GraphJsonCodec(), Runnable::run, new ScheduledExecutorTickScheduler());
        this.engine.update(graph());
    }

    private WireGraphConfiguration graph() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID);
        final ComponentConfiguration configuration = new ComponentConfiguration(PID, null, properties);
        return new WireGraphConfiguration(List.of(new WireComponentConfiguration(configuration, Map.of(
                WireGraphConstants.INPUT_PORT_COUNT, 0, WireGraphConstants.OUTPUT_PORT_COUNT, 1))), List.of());
    }

    /** 造一个「每 100ms 一刻」的 CRON 发射器：next 严格取 after 之后的下一个 100ms 边界。 */
    final class QuantizedCronFactory implements WireComponentFactory {

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
            return new QuantizedCronEmitter(pid, WireGraphEngineCronCadenceTest.this.ticks);
        }
    }

    static final class QuantizedCronEmitter implements WireComponent, ScheduledEmitter {

        private final String pid;
        private final List<Instant> ticks;

        QuantizedCronEmitter(final String pid, final List<Instant> ticks) {
            this.pid = pid;
            this.ticks = ticks;
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public void tick() {
            this.ticks.add(Instant.now());
        }

        @Override
        public boolean isPeriodic() {
            return false;
        }

        @Override
        public long getInitialDelayMillis() {
            return 0L;
        }

        @Override
        public long getPeriodMillis() {
            return QUANTUM_MILLIS;
        }

        @Override
        public Instant nextCronFireTime(final Instant after) {
            final long nextBoundary = (after.toEpochMilli() / QUANTUM_MILLIS + 1L) * QUANTUM_MILLIS;
            return Instant.ofEpochMilli(nextBoundary);
        }
    }
}
