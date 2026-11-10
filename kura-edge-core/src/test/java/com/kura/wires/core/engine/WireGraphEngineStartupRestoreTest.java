/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
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
import com.kura.wires.core.testsupport.GraphFixtures;
import com.kura.wires.core.testsupport.InMemoryComponentConfigurationService;
import com.kura.wires.core.testsupport.InMemorySnapshotStore;
import com.kura.wires.core.testsupport.MutableClock;
import com.kura.wires.core.testsupport.StubWireComponentFactory;
import com.kura.wires.engine.ScheduledEmitter;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.type.LongValue;
import com.kura.wires.model.WireRecord;

/**
 * 进程重启后的整图恢复（Gherkin 风格，注入假调度器，不依赖真实时钟）：
 * 图快照与组件配置都在库里，重启后必须按快照重建组件实例并重新挂上定时器，
 * 否则「保存后重启服务，Wire Graph 的定时器不工作」——图还画得出来，定时器却一声不响。
 */
class WireGraphEngineStartupRestoreTest {

    private static final String TIMER_PID = "timer";
    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";
    private static final String SINK_PID = "sink";
    private static final String SINK_FACTORY_PID = "test.sink";
    private static final Instant STARTUP_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private ComponentConfigurationService configurationService;
    private SnapshotStore snapshotStore;

    @BeforeEach
    public void setUp() {
        // 配置服务与快照存储跨「重启」共享：等价于同一个 H2 库
        this.configurationService = new InMemoryComponentConfigurationService();
        this.snapshotStore = new InMemorySnapshotStore();
    }

    @Test
    public void restartRestoresPersistedTimerAndResumesTicks() {
        final Boot beforeRestart = bootTimerOnly();
        givenTimerSavedWithInterval(beforeRestart.engine(), 1000L);

        final Boot restarted = bootTimerOnly();
        whenEngineRestoredAfterRestart(restarted.engine());

        thenInstanceRunning(restarted, TIMER_PID);
        thenPeriodicTickScheduledWithPeriod(restarted.scheduler(), 1000L);
        whenScheduledTickFires(restarted.scheduler());
        thenTimerTicked(restarted.timerFactory(), 1);
    }

    @Test
    public void restartRestoresCronTimerAsOneShotSchedule() {
        final Boot beforeRestart = bootTimerOnly();
        givenCronTimerSaved(beforeRestart.engine());

        final Boot restarted = bootTimerOnly();
        whenEngineRestoredAfterRestart(restarted.engine());

        thenInstanceRunning(restarted, TIMER_PID);
        assertThat(restarted.scheduler().scheduleCount).isEqualTo(1);
        // CRON 走「排一次、到点再排下一次」的链，重启后必须重新把第一环排上
        assertThat(restarted.scheduler().pendingPeriod).isNull();
    }

    @Test
    public void restartRestoresWiresSoRestoredTimerStillReachesItsReceiver() {
        final Boot beforeRestart = bootTimerAndSink();
        givenTimerWiredToSinkSavedWithInterval(beforeRestart.engine(), 1000L);

        final Boot restarted = bootTimerAndSink();
        whenEngineRestoredAfterRestart(restarted.engine());
        whenScheduledTickFires(restarted.scheduler());

        thenSinkReceived(restarted.sinkFactory(), 1);
    }

    @Test
    public void restartWithoutPersistedSnapshotRestoresNothing() {
        final Boot restarted = bootTimerOnly();

        whenEngineRestoredAfterRestart(restarted.engine());

        thenNoInstanceRunning(restarted, TIMER_PID);
        assertThat(restarted.scheduler().scheduleCount).isZero();
    }

    @Test
    public void restartSkipsComponentWhoseFactoryIsNoLongerRegistered() {
        final Boot beforeRestart = bootTimerAndSink();
        givenTimerWiredToSinkSavedWithInterval(beforeRestart.engine(), 1000L);

        // 重启后 sink 的工厂不存在（组件被卸载/授权缺失）：只跳过该组件，不阻断整图恢复
        final Boot restarted = bootTimerOnly();
        assertThatCode(() -> whenEngineRestoredAfterRestart(restarted.engine())).doesNotThrowAnyException();

        thenInstanceRunning(restarted, TIMER_PID);
        thenPeriodicTickScheduledWithPeriod(restarted.scheduler(), 1000L);
        assertThat(restarted.registry().getInstance(SINK_PID)).isEmpty();
    }

    @Test
    public void restartRestoreIsIdempotent() {
        final Boot beforeRestart = bootTimerOnly();
        givenTimerSavedWithInterval(beforeRestart.engine(), 1000L);

        final Boot restarted = bootTimerOnly();
        whenEngineRestoredAfterRestart(restarted.engine());
        whenEngineRestoredAfterRestart(restarted.engine());

        thenInstanceRunning(restarted, TIMER_PID);
        assertThat(restarted.timerFactory().emitters).hasSize(1);
    }

    private Boot bootTimerOnly() {
        final EmitterFactory timerFactory = new EmitterFactory();
        return boot(timerFactory, List.of(timerFactory));
    }

    private Boot bootTimerAndSink() {
        final EmitterFactory timerFactory = new EmitterFactory();
        final StubWireComponentFactory sinkFactory = new StubWireComponentFactory(SINK_FACTORY_PID, 1, 0);
        return boot(timerFactory, List.of(timerFactory, sinkFactory), sinkFactory);
    }

    private Boot boot(final EmitterFactory timerFactory, final List<WireComponentFactory> factories) {
        return boot(timerFactory, factories, null);
    }

    private Boot boot(final EmitterFactory timerFactory, final List<WireComponentFactory> factories,
            final StubWireComponentFactory sinkFactory) {
        final WireComponentRegistry registry = new WireComponentRegistry(factories);
        final RecordingScheduler scheduler = new RecordingScheduler();
        final WireGraphEngine engine = new WireGraphEngine(registry, this.configurationService, this.snapshotStore,
                new GraphJsonCodec(), Runnable::run, scheduler, new MutableClock(STARTUP_INSTANT));
        return new Boot(registry, scheduler, engine, timerFactory, sinkFactory);
    }

    private void givenTimerSavedWithInterval(final WireGraphEngine engine, final long intervalMillis) {
        engine.update(GraphFixtures.graphOf(timerComponent(intervalMillis)));
    }

    private void givenCronTimerSaved(final WireGraphEngine engine) {
        engine.update(GraphFixtures.graphOf(timerComponent("CRON", 0L)));
    }

    private void givenTimerWiredToSinkSavedWithInterval(final WireGraphEngine engine, final long intervalMillis) {
        engine.update(GraphFixtures.graph(List.of(timerComponent(intervalMillis),
                GraphFixtures.component(SINK_PID, SINK_FACTORY_PID, 1, 0)),
                List.of(GraphFixtures.wire(TIMER_PID, SINK_PID))));
    }

    private void whenEngineRestoredAfterRestart(final WireGraphEngine engine) {
        engine.restoreGraph();
    }

    private void whenScheduledTickFires(final RecordingScheduler scheduler) {
        scheduler.pendingTask().run();
    }

    private void thenInstanceRunning(final Boot boot, final String pid) {
        assertThat(boot.registry().getInstance(pid)).isPresent();
    }

    private void thenNoInstanceRunning(final Boot boot, final String pid) {
        assertThat(boot.registry().getInstance(pid)).isEmpty();
    }

    private void thenPeriodicTickScheduledWithPeriod(final RecordingScheduler scheduler, final long periodMillis) {
        assertThat(scheduler.scheduleCount).isEqualTo(1);
        assertThat(scheduler.pendingPeriod).isEqualTo(periodMillis);
    }

    private void thenTimerTicked(final EmitterFactory timerFactory, final int expectedTicks) {
        assertThat(timerFactory.emitters).hasSize(1);
        assertThat(timerFactory.emitters.get(0).ticks.get()).isEqualTo(expectedTicks);
    }

    private void thenSinkReceived(final StubWireComponentFactory sinkFactory, final int expectedEnvelopes) {
        assertThat(sinkFactory.created(SINK_PID)).isPresent();
        assertThat(sinkFactory.created(SINK_PID).orElseThrow().getReceived()).hasSize(expectedEnvelopes);
    }

    /**
     * 图内 Timer 组件：运行期属性（类型/间隔）随组件配置持久化，端口数随图 JSON 持久化，
     * 与上游「组件配置 + 渲染属性」两段式一致。
     */
    private static WireComponentConfiguration timerComponent(final long intervalMillis) {
        return timerComponent("SIMPLE", intervalMillis);
    }

    private static WireComponentConfiguration timerComponent(final String type, final long intervalMillis) {
        final Map<String, Object> runtimeProperties = new LinkedHashMap<>();
        runtimeProperties.put(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID);
        runtimeProperties.put("type", type);
        runtimeProperties.put("interval", intervalMillis);
        final Map<String, Object> graphProperties = new LinkedHashMap<>();
        graphProperties.put(WireGraphConstants.INPUT_PORT_COUNT, 0);
        graphProperties.put(WireGraphConstants.OUTPUT_PORT_COUNT, 1);
        return new WireComponentConfiguration(
                new ComponentConfiguration(TIMER_PID, null, runtimeProperties), graphProperties);
    }

    /** 一次「进程生命周期」内的引擎与它的协作对象（重启 = 再 boot 一份，只有库是共享的）。 */
    private record Boot(WireComponentRegistry registry, RecordingScheduler scheduler, WireGraphEngine engine,
            EmitterFactory timerFactory, StubWireComponentFactory sinkFactory) {
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
            final StubEmitter emitter = (simple
                    ? StubEmitter.periodic(pid, ((Number) properties.get("interval")).longValue())
                    : StubEmitter.cron(pid)).withEmitContext(emitContext);
            this.emitters.add(emitter);
            return emitter;
        }
    }

    /** 只记 tick 次数的自驱动组件桩（对照 {@code Timer} 的 {@link ScheduledEmitter} 契约）。 */
    static final class StubEmitter implements WireComponent, ScheduledEmitter {

        private final String pid;
        private final boolean periodic;
        private final long periodMillis;
        private final AtomicInteger ticks = new AtomicInteger();
        private WireEmitContext emitContext;

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

        StubEmitter withEmitContext(final WireEmitContext context) {
            this.emitContext = context;
            return this;
        }

        @Override
        public void tick() {
            this.ticks.incrementAndGet();
            this.emitContext.emit(new WireEnvelope(this.pid,
                    List.of(new WireRecord(Map.of("TIMER", new LongValue(0L))))));
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

    /** 假调度器：只记排了什么任务，由测试决定何时「到点」（不依赖真实时钟，不会 flaky）。 */
    static final class RecordingScheduler implements TickScheduler {

        private final Map<String, Runnable> pending = new LinkedHashMap<>();
        private final List<String> cancelledPids = new ArrayList<>();
        private int scheduleCount;
        private Long pendingPeriod;

        @Override
        public void schedulePeriodic(final String pid, final Runnable task, final long initialDelayMillis,
                final long periodMillis) {
            this.scheduleCount++;
            this.pendingPeriod = periodMillis;
            this.pending.put(pid, task);
        }

        @Override
        public void scheduleOnce(final String pid, final Runnable task, final long delayMillis) {
            this.scheduleCount++;
            this.pendingPeriod = null;
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
            return Optional.ofNullable(this.pending.get(TIMER_PID))
                    .orElseThrow(() -> new AssertionError("no tick scheduled for " + TIMER_PID));
        }
    }
}
