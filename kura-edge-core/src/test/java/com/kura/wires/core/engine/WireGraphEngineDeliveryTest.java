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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.WireReceiver;
import com.kura.wires.model.definition.Ocd;

/**
 * 引擎级投递并发行为（RFC 004，真实调度器 + 真实线程池）：
 * 慢接收器不会被并发重入，且积压超出该组件排队上限时丢新信封并计数。
 */
class WireGraphEngineDeliveryTest {

    private static final String TIMER_PID = "timer";
    private static final String RECEIVER_PID = "receiver";
    private static final String TIMER_FACTORY_PID = "stub.periodic";
    private static final String RECEIVER_FACTORY_PID = "stub.slow.receiver";

    private final AtomicInteger concurrent = new AtomicInteger();

    private final AtomicInteger maxConcurrent = new AtomicInteger();

    private final AtomicInteger deliveries = new AtomicInteger();

    private WireGraphEngine engine;

    private ExecutorService pool;

    @AfterEach
    public void tearDown() {
        if (this.engine != null) {
            this.engine.shutdown();
        }
        if (this.pool != null) {
            this.pool.shutdownNow();
        }
    }

    @Test
    public void successfulSlowReceiverIsNeverEnteredConcurrently() throws Exception {
        givenEngineWithBoundedDelivery(new DeliverySettings(4, 64, 16), 100L, 120L);

        whenRunningTicksFor(900L);

        thenMaxConcurrencyIs(1);
        assertThat(this.deliveries).as("拍数应持续累加").hasValueGreaterThanOrEqualTo(3);
        assertThat(this.engine.getDeliveryStats().dropped()).as("排队上限内不应丢信封").isZero();
    }

    @Test
    public void failedBacklogBeyondReceiverQueueDropsNewestEnvelopes() throws Exception {
        givenEngineWithBoundedDelivery(new DeliverySettings(1, 64, 1), 20L, 250L);

        whenRunningTicksFor(1000L);

        final DeliveryStats stats = this.engine.getDeliveryStats();
        thenMaxConcurrencyIs(1);
        assertThat(stats.droppedByReceiverQueue()).as("超出排队上限的信封应被丢弃并计数").isPositive();
        assertThat(stats.delivered()).isLessThan(stats.submitted());
    }

    private void whenRunningTicksFor(final long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void thenMaxConcurrencyIs(final int expected) {
        assertThat(this.maxConcurrent.get()).isEqualTo(expected);
    }

    private void givenEngineWithBoundedDelivery(final DeliverySettings settings, final long periodMillis,
            final long processingMillis) {
        final ComponentConfigurationService configurationService = new InMemoryComponentConfigurationService();
        final WireComponentRegistry registry = new WireComponentRegistry(
                List.of(timerFactory(periodMillis), receiverFactory(processingMillis)));
        final SnapshotStore snapshotStore = mock(SnapshotStore.class);
        final Map<String, String> snapshots = new HashMap<>();
        when(snapshotStore.load(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(snapshots.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> {
            snapshots.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(snapshotStore).save(anyString(), anyString());
        this.pool = Executors.newFixedThreadPool(settings.threads());
        this.engine = new WireGraphEngine(registry, configurationService, snapshotStore, new GraphJsonCodec(),
                this.pool, new ScheduledExecutorTickScheduler(), java.time.Clock.systemUTC(), settings);
        this.engine.update(graph());
    }

    private WireGraphConfiguration graph() {
        final Map<String, Object> timerProperties = new LinkedHashMap<>();
        timerProperties.put(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID);
        final Map<String, Object> receiverProperties = new LinkedHashMap<>();
        receiverProperties.put(WireGraphConstants.SERVICE_FACTORY_PID, RECEIVER_FACTORY_PID);
        return new WireGraphConfiguration(List.of(
                new WireComponentConfiguration(new ComponentConfiguration(TIMER_PID, null, timerProperties),
                        Map.of(WireGraphConstants.INPUT_PORT_COUNT, 0, WireGraphConstants.OUTPUT_PORT_COUNT, 1)),
                new WireComponentConfiguration(new ComponentConfiguration(RECEIVER_PID, null, receiverProperties),
                        Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1, WireGraphConstants.OUTPUT_PORT_COUNT, 0))),
                List.of(new MultiportWireConfiguration(TIMER_PID, RECEIVER_PID, 0, 0)));
    }

    private WireComponentFactory timerFactory(final long periodMillis) {
        return new TestFactory(TIMER_FACTORY_PID, 0, 1) {
            @Override
            public WireComponent create(final String pid, final Map<String, Object> properties,
                    final WireEmitContext emitContext) {
                return new PeriodicEmitter(pid, emitContext, periodMillis);
            }
        };
    }

    private WireComponentFactory receiverFactory(final long processingMillis) {
        return new TestFactory(RECEIVER_FACTORY_PID, 1, 0) {
            @Override
            public WireComponent create(final String pid, final Map<String, Object> properties,
                    final WireEmitContext emitContext) {
                return new SlowReceiver(pid, processingMillis);
            }
        };
    }

    /** 简易工厂基类：端口数与工厂 PID 固定，OCD 用空定义。 */
    private abstract static class TestFactory implements WireComponentFactory {

        private final String factoryPid;

        private final int inputPorts;

        private final int outputPorts;

        TestFactory(final String factoryPid, final int inputPorts, final int outputPorts) {
            this.factoryPid = factoryPid;
            this.inputPorts = inputPorts;
            this.outputPorts = outputPorts;
        }

        @Override
        public String factoryPid() {
            return this.factoryPid;
        }

        @Override
        public int defaultInputPortCount() {
            return this.inputPorts;
        }

        @Override
        public int defaultOutputPortCount() {
            return this.outputPorts;
        }

        @Override
        public Ocd getDefinition() {
            return new Ocd();
        }
    }

    /** 固定周期的自驱动发射器。 */
    private final class PeriodicEmitter implements WireComponent, ScheduledEmitter {

        private final String pid;

        private final WireEmitContext emitContext;

        private final long periodMillis;

        PeriodicEmitter(final String pid, final WireEmitContext emitContext, final long periodMillis) {
            this.pid = pid;
            this.emitContext = emitContext;
            this.periodMillis = periodMillis;
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public void tick() {
            this.emitContext.emit(new WireEnvelope(this.pid, List.of()));
        }

        @Override
        public boolean isPeriodic() {
            return true;
        }

        @Override
        public long getInitialDelayMillis() {
            return this.periodMillis;
        }

        @Override
        public long getPeriodMillis() {
            return this.periodMillis;
        }

        @Override
        public Instant nextCronFireTime(final Instant after) {
            return after.plusMillis(this.periodMillis);
        }
    }

    /** 处理较慢的接收器：记录最大并发与投递数，模拟驱动读写耗时。 */
    private final class SlowReceiver implements WireReceiver {

        private final String pid;

        private final long processingMillis;

        SlowReceiver(final String pid, final long processingMillis) {
            this.pid = pid;
            this.processingMillis = processingMillis;
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public void onWireReceive(final WireEnvelope envelope) {
            final int inFlight = WireGraphEngineDeliveryTest.this.concurrent.incrementAndGet();
            WireGraphEngineDeliveryTest.this.maxConcurrent.accumulateAndGet(inFlight, Math::max);
            WireGraphEngineDeliveryTest.this.deliveries.incrementAndGet();
            try {
                Thread.sleep(this.processingMillis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                WireGraphEngineDeliveryTest.this.concurrent.decrementAndGet();
            }
        }
    }
}
