/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.registry.EnvelopeDispatcher;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.registry.WireComponentRegistryListener;
import com.kura.wires.core.snapshot.GraphJsonCodec;
import com.kura.wires.core.snapshot.SnapshotStore;
import com.kura.wires.core.testsupport.MutableClock;
import com.kura.wires.engine.DrainableComponent;
import com.kura.wires.engine.ScheduledEmitter;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.WireReceiver;
import com.kura.wires.model.WireRecord;

/**
 * 引擎侧调度行为测试（Gherkin 风格，注入假调度器，不依赖真实时钟）：
 * 自驱动组件的排程与取消、CRON 链式重排、调度失败隔离，以及缓冲组件的异步 drain 触发。
 */
class WireGraphEngineSchedulingTest {

    private static final String SERVICE_PID = WireGraphConstants.WIRE_GRAPH_SERVICE_PID;
    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";
    private static final String FIFO_FACTORY_PID = "org.eclipse.kura.wire.Fifo";

    private WireComponentRegistry registry;
    private ComponentConfigurationService configurationService;
    private SnapshotStore snapshotStore;
    private GraphJsonCodec codec;
    private FakeTickScheduler scheduler;
    private MutableClock clock;
    private WireGraphEngine engine;
    private WireComponentRegistryListener listener;
    private RuntimeException tickFailure;

    @BeforeEach
    public void setUp() {
        this.registry = mock(WireComponentRegistry.class);
        this.configurationService = mock(ComponentConfigurationService.class);
        this.snapshotStore = mock(SnapshotStore.class);
        this.codec = new GraphJsonCodec();
        this.scheduler = new FakeTickScheduler();
        this.clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        this.tickFailure = null;
        when(this.snapshotStore.load(SERVICE_PID)).thenReturn(Optional.empty());
        when(this.registry.findFactory(anyString())).thenReturn(Optional.empty());
        this.engine = new WireGraphEngine(this.registry, this.configurationService, this.snapshotStore, this.codec,
                Runnable::run, this.scheduler, this.clock);
        this.listener = capturedListener();
    }

    @Test
    public void successfulPeriodicEmitterScheduledOnInstanceCreation() {
        whenInstanceCreated(new StubPeriodicEmitter());

        thenPeriodicScheduled("timer-1", 500L, 1000L);
    }

    @Test
    public void successfulCronEmitterReschedulesAfterEachTick() {
        final StubCronEmitter emitter = new StubCronEmitter();

        whenInstanceCreated(emitter);
        thenPendingTaskExists("cron-1");
        whenRunningPendingTask("cron-1");
        whenRunningPendingTask("cron-1");

        thenTickCountIs(emitter, 2);
        thenPendingTaskExists("cron-1");
    }

    @Test
    public void successfulCronTickFailureKeepsScheduling() {
        final StubCronEmitter emitter = new StubCronEmitter();
        emitter.failOnTick = true;

        whenInstanceCreated(emitter);
        whenRunningPendingTask("cron-1");

        thenTickFailureWas("boom");
        thenPendingTaskExists("cron-1");
    }

    @Test
    public void successfulInstanceDestroyCancelsScheduling() {
        whenInstanceCreated(new StubPeriodicEmitter());

        whenInstanceDestroyed("timer-1");

        thenCancelled("timer-1");
    }

    @Test
    public void successfulGraphDeleteCancelsAllScheduling() {
        whenInstanceCreated(new StubPeriodicEmitter());

        this.engine.delete();

        thenCancelAllInvoked();
    }

    @Test
    public void successfulShutdownStopsScheduler() {
        whenInstanceCreated(new StubPeriodicEmitter());

        this.engine.shutdown();

        thenCancelAllInvoked();
        thenSchedulerShutDown();
    }

    @Test
    public void successfulPlainComponentIsNotScheduled() {
        whenInstanceCreated(new StubPlainComponent());

        thenNothingScheduled();
    }

    @Test
    public void successfulSchedulingFailureDoesNotEscape() {
        final StubCronEmitter emitter = new StubCronEmitter();
        emitter.invalidCron = true;

        assertThatCode(() -> whenInstanceCreated(emitter)).doesNotThrowAnyException();

        thenNothingScheduled();
    }

    @Test
    public void successfulDrainableReceiverIsDrainedAfterDelivery() {
        final StubDrainableReceiver receiver = new StubDrainableReceiver();
        givenGraphWithWire("timer-1", "fifo-1", receiver);

        whenDispatching("timer-1", sampleEnvelope());

        thenReceived(receiver, 1);
        thenDrained(receiver, 1);
    }

    private void whenInstanceCreated(final WireComponent instance) {
        this.listener.onInstanceCreated(instance.getPid(), TIMER_FACTORY_PID, instance);
    }

    private void whenInstanceDestroyed(final String pid) {
        this.listener.onInstanceDestroyed(pid, TIMER_FACTORY_PID);
    }

    private void whenRunningPendingTask(final String pid) {
        final Runnable task = this.scheduler.pending.get(pid);
        // 假调度器不真的等：把注入时钟推进到排程时刻，模拟「到点才跑」
        this.clock.advanceMillis(this.scheduler.delays.getOrDefault(pid, 0L));
        try {
            task.run();
        } catch (final RuntimeException e) {
            // 生产实现（ScheduledExecutorTickScheduler#guarded）会兜住任务异常，这里模拟同样的边界
            this.tickFailure = e;
        }
    }

    private void givenGraphWithWire(final String emitterPid, final String receiverPid, final WireComponent receiver) {
        final List<WireComponentConfiguration> components = List.of(
                componentConfiguration(emitterPid, TIMER_FACTORY_PID),
                componentConfiguration(receiverPid, FIFO_FACTORY_PID));
        final List<MultiportWireConfiguration> wires = List.of(
                new MultiportWireConfiguration(emitterPid, receiverPid, 0, 0));
        when(this.snapshotStore.load(SERVICE_PID))
                .thenReturn(Optional.of(this.codec.marshal(new WireGraphConfiguration(components, wires))));
        when(this.registry.getInstance(receiverPid)).thenReturn(Optional.of(receiver));
    }

    private static WireComponentConfiguration componentConfiguration(final String pid, final String factoryPid) {
        return new WireComponentConfiguration(
                new ComponentConfiguration(pid, null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid)),
                Map.of());
    }

    private void whenDispatching(final String emitterPid, final WireEnvelope envelope) {
        // 生产里图在启动/更新时载入缓存，实例变更回调再按缓存刷新路由表
        this.engine.get();
        whenInstanceCreated(new StubPeriodicEmitter());
        dispatcher().dispatch(emitterPid, envelope);
    }

    private EnvelopeDispatcher dispatcher() {
        final ArgumentCaptor<EnvelopeDispatcher> captor = ArgumentCaptor.forClass(EnvelopeDispatcher.class);
        verify(this.registry).setEnvelopeDispatcher(captor.capture());
        return captor.getValue();
    }

    private WireComponentRegistryListener capturedListener() {
        final ArgumentCaptor<WireComponentRegistryListener> captor =
                ArgumentCaptor.forClass(WireComponentRegistryListener.class);
        verify(this.registry).addListener(captor.capture());
        return captor.getValue();
    }

    private static WireEnvelope sampleEnvelope() {
        return new WireEnvelope("timer-1", List.of(new WireRecord(Map.of())));
    }

    private void thenPeriodicScheduled(final String pid, final long initialDelay, final long period) {
        assertThat(this.scheduler.delays.get(pid)).isEqualTo(initialDelay);
        assertThat(this.scheduler.periods.get(pid)).isEqualTo(period);
    }

    private void thenPendingTaskExists(final String pid) {
        assertThat(this.scheduler.pending).containsKey(pid);
    }

    private void thenNothingScheduled() {
        assertThat(this.scheduler.pending).isEmpty();
        assertThat(this.scheduler.periods).isEmpty();
    }

    private void thenTickCountIs(final StubCronEmitter emitter, final int expected) {
        assertThat(emitter.ticks).isEqualTo(expected);
    }

    private void thenTickFailureWas(final String message) {
        assertThat(this.tickFailure).isNotNull().hasMessage(message);
    }

    private void thenCancelled(final String pid) {
        assertThat(this.scheduler.cancelled).contains(pid);
    }

    private void thenCancelAllInvoked() {
        assertThat(this.scheduler.cancelAllCount).isEqualTo(1);
    }

    private void thenSchedulerShutDown() {
        assertThat(this.scheduler.shutdownCalled).isTrue();
    }

    private void thenReceived(final StubDrainableReceiver receiver, final int expected) {
        assertThat(receiver.received).isEqualTo(expected);
    }

    private void thenDrained(final StubDrainableReceiver receiver, final int expected) {
        assertThat(receiver.drains).isEqualTo(expected);
    }

    /** 假调度器：只记录排程，由测试手动驱动，便于断言不留时间依赖 */
    private static final class FakeTickScheduler implements TickScheduler {

        private final Map<String, Runnable> pending = new HashMap<>();
        private final Map<String, Long> delays = new HashMap<>();
        private final Map<String, Long> periods = new HashMap<>();
        private final List<String> cancelled = new ArrayList<>();
        private int cancelAllCount;
        private boolean shutdownCalled;

        @Override
        public void schedulePeriodic(final String pid, final Runnable task, final long initialDelayMillis,
                final long periodMillis) {
            this.pending.put(pid, task);
            this.delays.put(pid, initialDelayMillis);
            this.periods.put(pid, periodMillis);
        }

        @Override
        public void scheduleOnce(final String pid, final Runnable task, final long delayMillis) {
            this.pending.put(pid, task);
            this.delays.put(pid, delayMillis);
        }

        @Override
        public void cancel(final String pid) {
            this.cancelled.add(pid);
            this.pending.remove(pid);
        }

        @Override
        public void cancelAll() {
            this.cancelAllCount++;
            this.pending.clear();
        }

        @Override
        public void shutdown() {
            this.shutdownCalled = true;
        }
    }

    /** 固定速率自驱动组件桩 */
    private static final class StubPeriodicEmitter implements WireComponent, ScheduledEmitter {

        private int ticks;

        @Override
        public String getPid() {
            return "timer-1";
        }

        @Override
        public boolean isPeriodic() {
            return true;
        }

        @Override
        public long getInitialDelayMillis() {
            return 500L;
        }

        @Override
        public long getPeriodMillis() {
            return 1000L;
        }

        @Override
        public Instant nextCronFireTime(final Instant after) {
            throw new IllegalStateException("SIMPLE");
        }

        @Override
        public void tick() {
            this.ticks++;
        }
    }

    /** CRON 自驱动组件桩 */
    private static final class StubCronEmitter implements WireComponent, ScheduledEmitter {

        private int ticks;
        private boolean failOnTick;
        private boolean invalidCron;

        @Override
        public String getPid() {
            return "cron-1";
        }

        @Override
        public boolean isPeriodic() {
            return false;
        }

        @Override
        public long getInitialDelayMillis() {
            throw new IllegalStateException("CRON");
        }

        @Override
        public long getPeriodMillis() {
            throw new IllegalStateException("CRON");
        }

        @Override
        public Instant nextCronFireTime(final Instant after) {
            if (this.invalidCron) {
                throw new IllegalArgumentException("Invalid CRON expression");
            }
            return after.plusMillis(5);
        }

        @Override
        public void tick() {
            this.ticks++;
            if (this.failOnTick) {
                throw new IllegalStateException("boom");
            }
        }
    }

    /** 普通组件桩（不自驱动） */
    private static final class StubPlainComponent implements WireComponent {

        @Override
        public String getPid() {
            return "logger-1";
        }
    }

    /** 缓冲型接收组件桩（Fifo 语义） */
    private static final class StubDrainableReceiver implements WireReceiver, DrainableComponent {

        private int received;
        private int drains;

        @Override
        public String getPid() {
            return "fifo-1";
        }

        @Override
        public void onWireReceive(final WireEnvelope wireEnvelope) {
            this.received++;
        }

        @Override
        public void drain() {
            this.drains++;
        }
    }
}
