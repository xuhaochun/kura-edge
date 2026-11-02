/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.registry.EnvelopeDispatcher;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.registry.WireComponentRegistryListener;
import com.kura.wires.core.snapshot.GraphJsonCodec;
import com.kura.wires.core.snapshot.SnapshotStore;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ocd;

/**
 * {@link WireGraphEngine} 的行为测试：图读取、更新（校验/diff/回滚/落库）、
 * 删除与信封投递的正反路径（依赖以 Mockito 模拟，快照 JSON 用真实
 * {@link GraphJsonCodec} 构造，保证往返语义）。
 */
public class WireGraphEngineTest {

    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";
    private static final String LOGGER_FACTORY_PID = "org.eclipse.kura.wire.Logger";
    private static final String SERVICE_PID = WireGraphConstants.WIRE_GRAPH_SERVICE_PID;

    private final List<WireComponentConfiguration> components = new CopyOnWriteArrayList<>();
    private final List<MultiportWireConfiguration> wires = new CopyOnWriteArrayList<>();
    private WireComponentRegistry registry;
    private ComponentConfigurationService configurationService;
    private SnapshotStore snapshotStore;
    private GraphJsonCodec codec;
    private StubComponentFactory timerFactory;
    private WireGraphEngine engine;
    private WireGraphConfiguration graph;
    private String savedJson;
    private Exception caught;

    @BeforeEach
    public void setUp() {
        this.registry = mock(WireComponentRegistry.class);
        this.configurationService = mock(ComponentConfigurationService.class);
        this.snapshotStore = mock(SnapshotStore.class);
        this.codec = new GraphJsonCodec();
        this.timerFactory = new StubComponentFactory(TIMER_FACTORY_PID, 1, 1);
        this.savedJson = null;
        this.caught = null;
    }

    @Test
    public void successfulGetReturnsEmptyGraphWhenNoSnapshotExists() {
        givenEmptySnapshot();
        givenEngineStarted();

        whenGettingGraph();

        assertThat(this.graph.getWireComponentConfigurations()).isEmpty();
        assertThat(this.graph.getWireConfigurations()).isEmpty();
    }

    @Test
    public void successfulGetReturnsGraphFromSnapshot() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();

        whenGettingGraph();

        assertThat(this.graph.getWireComponentConfigurations()).hasSize(1);
        assertThat(this.graph.getWireComponentConfigurations().get(0).getConfiguration().getPid()).isEqualTo("timer");
    }

    @Test
    public void rejectEngineStartOnCorruptedSnapshot() {
        when(this.snapshotStore.load(SERVICE_PID)).thenReturn(Optional.of("{not a graph"));

        assertThatThrownBy(this::givenEngineStarted).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_INVALID);
    }

    @Test
    public void successfulUpdateCreatesNewComponentsAndSavesSnapshot() {
        givenEmptySnapshot();
        givenFactoryRegistered(TIMER_FACTORY_PID);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.POSITION_X, 1.5));
        givenEngineStarted();

        whenUpdatingGraph();

        final ArgumentCaptor<Map<String, Object>> instanceProps = ArgumentCaptor.forClass(Map.class);
        verify(this.registry).createInstance(eq("timer"), eq(TIMER_FACTORY_PID), instanceProps.capture());
        assertThat(instanceProps.getValue()).containsEntry(WireGraphConstants.INPUT_PORT_COUNT, 1)
                .containsEntry(WireGraphConstants.OUTPUT_PORT_COUNT, 1)
                .containsEntry(WireGraphConstants.POSITION_X, 1.5);
        final ArgumentCaptor<Map<String, Object>> configProps = ArgumentCaptor.forClass(Map.class);
        verify(this.configurationService).createFactoryConfiguration(eq(TIMER_FACTORY_PID), eq("timer"),
                configProps.capture());
        assertThat(configProps.getValue())
                .containsEntry(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID)
                .containsEntry(WireGraphEngine.RECEIVER_PORT_COUNT_PROP, 1)
                .containsEntry(WireGraphEngine.EMITTER_PORT_COUNT_PROP, 1);
        thenSnapshotSavedWithGraph();
        assertThat(this.codec.unmarshal(this.savedJson).getWireComponentConfigurations()).hasSize(1);
    }

    @Test
    public void successfulUpdateDeletesRemovedComponents() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        this.components.clear();

        whenUpdatingGraph();

        verify(this.registry).destroyInstance("timer");
        verify(this.configurationService).deleteFactoryConfiguration("timer");
        verify(this.registry, never()).createInstance(anyString(), anyString(), any());
        thenSnapshotSavedWithGraph();
        assertThat(this.codec.unmarshal(this.savedJson).getWireComponentConfigurations()).isEmpty();
    }

    @Test
    public void successfulUpdateRefreshesRetainedComponents() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        this.components.clear();
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1,
                WireGraphConstants.POSITION_X, 9.0));

        whenUpdatingGraph();

        final ArgumentCaptor<Map<String, Object>> configProps = ArgumentCaptor.forClass(Map.class);
        verify(this.configurationService).updateConfiguration(eq("timer"), configProps.capture());
        assertThat(configProps.getValue()).containsEntry(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID);
        verify(this.registry, never()).createInstance(anyString(), anyString(), any());
        verify(this.registry, never()).destroyInstance(anyString());
        thenSnapshotSavedWithGraph();
        assertThat(this.codec.unmarshal(this.savedJson).getWireComponentConfigurations().get(0).getProperties())
                .containsEntry(WireGraphConstants.POSITION_X, 9.0f);
    }

    @Test
    public void successfulUpdateBindsDispatcherAndListenerOnStart() {
        givenEmptySnapshot();
        givenEngineStarted();

        verify(this.registry).setEnvelopeDispatcher(any(EnvelopeDispatcher.class));
        verify(this.registry).addListener(any(WireComponentRegistryListener.class));
    }

    @Test
    public void rejectUpdateWhenGraphIsNull() {
        givenEmptySnapshot();
        givenEngineStarted();

        thenUpdateFailsWithCode(null, ErrorCode.CONFIG_INVALID);
        verify(this.registry, never()).createInstance(anyString(), anyString(), any());
    }

    @Test
    public void rejectUpdateWhenComponentHasNoPid() {
        givenEmptySnapshot();
        givenComponentWithoutPid();
        givenEngineStarted();

        thenUpdateFailsWithCode(ErrorCode.CONFIG_INVALID);
    }

    @Test
    public void rejectUpdateWhenCycleDetected() {
        givenEmptySnapshot();
        givenComponent("timer", TIMER_FACTORY_PID,
                Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1, WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID,
                Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1, WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenWire("logger", "timer", 0, 0);
        givenEngineStarted();

        thenUpdateFailsWithCode(ErrorCode.TOPOLOGY_CYCLE);
        verify(this.configurationService, never()).createFactoryConfiguration(anyString(), anyString(), any());
        verify(this.snapshotStore, never()).save(anyString(), anyString());
    }

    @Test
    public void rejectUpdateWhenEmitterPortOutOfRange() {
        givenEmptySnapshot();
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 5, 0);
        givenEngineStarted();

        thenUpdateFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectUpdateWhenWireReferencesUnknownComponent() {
        givenEmptySnapshot();
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenWire("timer", "missing", 0, 0);
        givenEngineStarted();

        thenUpdateFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectUpdateWhenNewComponentHasNoFactoryPid() {
        givenEmptySnapshot();
        givenComponent("timer", null, Map.of());
        givenEngineStarted();

        thenUpdateFailsWithCode(ErrorCode.CONFIG_INVALID);
    }

    @Test
    public void successfulUpdateRollsBackWhenFactoryConfigurationCreationFails() {
        givenEmptySnapshot();
        givenFactoryRegistered(TIMER_FACTORY_PID);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of());
        givenEngineStarted();
        doThrow(new WiresException(ErrorCode.PERSISTENCE_ERROR, "db down"))
                .when(this.configurationService).createFactoryConfiguration(anyString(), anyString(), any());

        thenUpdateFailsWithCode(ErrorCode.ENGINE_ERROR);
        assertThat(this.caught.getMessage()).contains("rolled back").contains("db down");
        verify(this.registry).destroyInstance("timer");
        verify(this.snapshotStore, never()).save(anyString(), anyString());
    }

    @Test
    public void successfulUpdateRollsBackDestroyedComponentsWhenCreationFails() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        this.components.clear();
        this.wires.clear();
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("publisher", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "publisher", 0, 0);
        doThrow(new WiresException(ErrorCode.PERSISTENCE_ERROR, "db down"))
                .when(this.configurationService).createFactoryConfiguration(anyString(), anyString(), any());

        thenUpdateFailsWithCode(ErrorCode.ENGINE_ERROR);
        assertThat(this.caught.getMessage()).contains("db down");

        verify(this.registry).destroyInstance("logger");
        verify(this.registry).createInstance(eq("publisher"), eq(LOGGER_FACTORY_PID), any());
        verify(this.registry).destroyInstance("publisher");
        verify(this.registry).createInstance(eq("logger"), eq(LOGGER_FACTORY_PID), any());
        verify(this.snapshotStore, never()).save(anyString(), anyString());
    }

    @Test
    public void successfulUpdateRollsBackWhenInstanceCreationFails() {
        givenEmptySnapshot();
        givenFactoryRegistered(TIMER_FACTORY_PID);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of());
        givenEngineStarted();
        when(this.registry.createInstance(anyString(), anyString(), any()))
                .thenThrow(new WiresException(ErrorCode.ENGINE_ERROR, "instance boom"));

        thenUpdateFailsWithCode(ErrorCode.ENGINE_ERROR);
        verify(this.configurationService, never()).createFactoryConfiguration(anyString(), anyString(), any());
        verify(this.snapshotStore, never()).save(anyString(), anyString());
    }

    @Test
    public void successfulDeleteRemovesAllInstancesConfigurationsAndSnapshot() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();

        whenDeletingGraph();

        verify(this.registry).destroyInstance("timer");
        verify(this.registry).destroyInstance("logger");
        verify(this.configurationService).deleteFactoryConfiguration("timer");
        verify(this.configurationService).deleteFactoryConfiguration("logger");
        verify(this.snapshotStore).delete(SERVICE_PID);
    }

    @Test
    public void successfulDeleteAggregatesPartialFailures() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        doThrow(new WiresException(ErrorCode.NOT_FOUND, "gone")).when(this.configurationService)
                .deleteFactoryConfiguration("timer");

        whenDeletingGraph();

        verify(this.snapshotStore).delete(SERVICE_PID);
        assertThat(this.caught).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.ENGINE_ERROR);
        assertThat(this.caught.getMessage()).contains("timer").contains("gone");
    }

    @Test
    public void successfulDispatchDeliversEnvelopeAlongWires() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        final RecordingReceiver receiver = new RecordingReceiver("logger");
        when(this.registry.getInstance("logger")).thenReturn(Optional.of(receiver));
        final EnvelopeDispatcher dispatcher = capturedDispatcher();

        dispatcher.dispatch("timer", newEnvelope("timer"));

        assertThat(receiver.received).hasSize(1);
        assertThat(receiver.received.get(0).getEmitterPid()).isEqualTo("timer");
    }

    @Test
    public void successfulDispatchSkipsUnknownReceiverAndEmitter() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        when(this.registry.getInstance("logger")).thenReturn(Optional.empty());
        final EnvelopeDispatcher dispatcher = capturedDispatcher();

        assertThatCode(() -> {
            dispatcher.dispatch("timer", newEnvelope("timer"));
            dispatcher.dispatch("unknown-emitter", newEnvelope("unknown-emitter"));
        }).doesNotThrowAnyException();
    }

    @Test
    public void successfulDispatchSwallowsReceiverFailure() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        final RecordingReceiver receiver = new RecordingReceiver("logger");
        receiver.failOnReceive = true;
        when(this.registry.getInstance("logger")).thenReturn(Optional.of(receiver));
        final EnvelopeDispatcher dispatcher = capturedDispatcher();

        assertThatCode(() -> dispatcher.dispatch("timer", newEnvelope("timer"))).doesNotThrowAnyException();
        assertThat(receiver.received).hasSize(1);
    }

    @Test
    public void successfulInstanceListenerRefreshesRouting() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenSnapshotOfCurrentFixture();
        givenEngineStarted();
        final RecordingReceiver receiver = new RecordingReceiver("logger");
        when(this.registry.getInstance("logger")).thenReturn(Optional.of(receiver));
        final ArgumentCaptor<WireComponentRegistryListener> listener = ArgumentCaptor
                .forClass(WireComponentRegistryListener.class);
        verify(this.registry).addListener(listener.capture());

        listener.getValue().onInstanceCreated("logger", LOGGER_FACTORY_PID, receiver);
        listener.getValue().onInstanceDestroyed("logger", LOGGER_FACTORY_PID);
        capturedDispatcher().dispatch("timer", newEnvelope("timer"));

        assertThat(receiver.received).hasSize(1);
    }

    @Test
    public void successfulAsyncDeliveryThroughPublicConstructor() throws Exception {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);
        givenSnapshotOfCurrentFixture();
        final RecordingReceiver receiver = new RecordingReceiver("logger");
        receiver.latch = new CountDownLatch(1);
        when(this.registry.getInstance("logger")).thenReturn(Optional.of(receiver));
        this.engine = new WireGraphEngine(this.registry, this.configurationService, this.snapshotStore, this.codec);

        capturedDispatcher().dispatch("timer", newEnvelope("timer"));
        assertThat(receiver.latch.await(5, TimeUnit.SECONDS)).isTrue();

        this.engine.shutdown();
    }

    private void givenEngineStarted() {
        this.engine = new WireGraphEngine(this.registry, this.configurationService, this.snapshotStore, this.codec,
                Runnable::run);
    }

    private void givenEmptySnapshot() {
        when(this.snapshotStore.load(SERVICE_PID)).thenReturn(Optional.empty());
    }

    private void givenFactoryRegistered(final String factoryPid) {
        when(this.registry.findFactory(factoryPid)).thenReturn(Optional.of(this.timerFactory));
    }

    private void givenComponent(final String pid, final String factoryPid, final Map<String, Object> graphProperties) {
        final Map<String, Object> configProperties = factoryPid == null ? Map.of()
                : Map.of(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid);
        final ComponentConfiguration configuration = new ComponentConfiguration(pid, null, configProperties);
        this.components.add(new WireComponentConfiguration(configuration, graphProperties));
        // 图 JSON 与上游一致只带 PID/端口数/渲染属性，组件配置属性由配置服务回填，
        // 故组件配置必须同时登记到配置服务（生产语义：引擎把工厂配置写库后读回）。
        when(this.configurationService.getConfiguration(pid)).thenReturn(Optional.of(configuration));
    }

    private void givenComponentWithoutPid() {
        this.components.add(new WireComponentConfiguration(new ComponentConfiguration(null, null, Map.of()), Map.of()));
    }

    private void givenWire(final String emitterPid, final String receiverPid, final int emitterPort,
            final int receiverPort) {
        this.wires.add(new MultiportWireConfiguration(emitterPid, receiverPid, emitterPort, receiverPort));
    }

    private void givenSnapshotOfCurrentFixture() {
        when(this.snapshotStore.load(SERVICE_PID))
                .thenReturn(Optional.of(this.codec.marshal(currentFixtureGraph())));
    }

    private void whenGettingGraph() {
        this.graph = this.engine.get();
    }

    private void whenUpdatingGraph() {
        this.engine.update(currentFixtureGraph());
    }

    private void whenDeletingGraph() {
        try {
            this.engine.delete();
        } catch (final Exception e) {
            this.caught = e;
        }
    }

    private void thenUpdateFailsWithCode(final WireGraphConfiguration configuration, final ErrorCode errorCode) {
        try {
            this.engine.update(configuration);
            throw new AssertionError("expected WiresException");
        } catch (final WiresException e) {
            this.caught = e;
            assertThat(e.getErrorCode()).isEqualTo(errorCode);
        }
    }

    private void thenUpdateFailsWithCode(final ErrorCode errorCode) {
        thenUpdateFailsWithCode(currentFixtureGraph(), errorCode);
    }

    private void thenSnapshotSavedWithGraph() {
        final ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(this.snapshotStore).save(eq(SERVICE_PID), json.capture());
        this.savedJson = json.getValue();
    }

    private EnvelopeDispatcher capturedDispatcher() {
        final ArgumentCaptor<EnvelopeDispatcher> dispatcher = ArgumentCaptor.forClass(EnvelopeDispatcher.class);
        verify(this.registry).setEnvelopeDispatcher(dispatcher.capture());
        return dispatcher.getValue();
    }

    private WireGraphConfiguration currentFixtureGraph() {
        return new WireGraphConfiguration(List.copyOf(this.components), List.copyOf(this.wires));
    }

    private static WireEnvelope newEnvelope(final String emitterPid) {
        return new WireEnvelope(emitterPid, List.of(new WireRecord(Map.of())));
    }

    /** 测试用工厂桩。 */
    private static final class StubComponentFactory implements WireComponentFactory {

        private final String factoryPid;
        private final int defaultInputPortCount;
        private final int defaultOutputPortCount;

        private StubComponentFactory(final String factoryPid, final int defaultInputPortCount,
                final int defaultOutputPortCount) {
            this.factoryPid = factoryPid;
            this.defaultInputPortCount = defaultInputPortCount;
            this.defaultOutputPortCount = defaultOutputPortCount;
        }

        @Override
        public String factoryPid() {
            return this.factoryPid;
        }

        @Override
        public int defaultInputPortCount() {
            return this.defaultInputPortCount;
        }

        @Override
        public int defaultOutputPortCount() {
            return this.defaultOutputPortCount;
        }

        @Override
        public Ocd getDefinition() {
            return new Ocd(this.factoryPid, this.factoryPid, "stub", null);
        }

        @Override
        public WireComponent create(final String pid, final Map<String, Object> properties,
                final WireEmitContext emitContext) {
            return new RecordingReceiver(pid);
        }
    }

    /** 测试用接收端组件桩：记录收到的信封，可注入失败与门闩。 */
    private static final class RecordingReceiver implements WireComponent {

        private final String pid;
        private final List<WireEnvelope> received = new CopyOnWriteArrayList<>();
        private volatile boolean failOnReceive;
        private volatile CountDownLatch latch;

        private RecordingReceiver(final String pid) {
            this.pid = pid;
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public void onWireReceive(final WireEnvelope envelope) {
            this.received.add(envelope);
            if (this.failOnReceive) {
                throw new IllegalStateException("receiver boom");
            }
            final CountDownLatch latch = this.latch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }
}
