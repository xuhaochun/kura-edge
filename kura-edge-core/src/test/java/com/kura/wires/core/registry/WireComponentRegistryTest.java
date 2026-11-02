/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ocd;

/**
 * {@link WireComponentRegistry} 的行为测试：工厂注册、实例创建/销毁、
 * 信封分发器回注与实例变更监听的正反路径。
 */
public class WireComponentRegistryTest {

    private static final String FACTORY_PID = "org.eclipse.kura.wire.Timer";
    private static final String PID = "timer_1";

    private WireComponentRegistry registry;
    private StubComponentFactory factory;
    private EnvelopeDispatcher dispatcher;
    private WireEnvelope envelope;

    @BeforeEach
    public void setUp() {
        this.dispatcher = mock(EnvelopeDispatcher.class);
        this.factory = new StubComponentFactory(FACTORY_PID, 1, 1, false);
        this.registry = new WireComponentRegistry(List.of(this.factory));
    }

    @Test
    public void successfulCreateAndLookupOfInstance() {
        givenFactoryRegistered(FACTORY_PID);

        whenCreatingInstance(PID, FACTORY_PID);

        thenInstanceIsTracked(PID, FACTORY_PID);
    }

    @Test
    public void successfulDestroyOfExistingInstance() {
        givenFactoryRegistered(FACTORY_PID);
        givenInstanceCreated(PID, FACTORY_PID);

        final boolean destroyed = this.registry.destroyInstance(PID);

        assertThat(destroyed).isTrue();
        assertThat(this.registry.getInstance(PID)).isEmpty();
        assertThat(this.registry.getFactoryPidOf(PID)).isEmpty();
        assertThat(this.registry.getInstances()).isEmpty();
    }

    @Test
    public void successfulDestroyOfUnknownInstanceReturnsFalse() {
        assertThat(this.registry.destroyInstance("unknown")).isFalse();
    }

    @Test
    public void successfulFactoryRegistrationAndLookup() {
        givenFactoryRegistered(FACTORY_PID);
        this.registry.registerFactory(null);

        assertThat(this.registry.getFactoryPids()).containsExactly(FACTORY_PID);
        assertThat(this.registry.findFactory(FACTORY_PID)).contains(this.factory);
        assertThat(this.registry.findFactory("missing")).isEmpty();
    }

    @Test
    public void successfulReregistrationOverridesPreviousFactory() {
        givenFactoryRegistered(FACTORY_PID);
        final StubComponentFactory replacement = new StubComponentFactory(FACTORY_PID, 2, 2, false);
        this.registry.registerFactory(replacement);

        assertThat(this.registry.findFactory(FACTORY_PID)).contains(replacement);
    }

    @Test
    public void successfulCreationWithEmptyConstructorFactoryList() {
        final WireComponentRegistry emptyRegistry = new WireComponentRegistry(null);

        assertThat(emptyRegistry.getFactoryPids()).isEmpty();
    }

    @Test
    public void rejectCreateInstanceWhenFactoryIsUnknown() {
        assertThatThrownBy(() -> this.registry.createInstance(PID, "missing.factory", Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void rejectCreateInstanceWhenFactoryFails() {
        givenFactoryRegistered(FACTORY_PID);
        this.factory.failOnCreate = true;

        assertThatThrownBy(() -> this.registry.createInstance(PID, FACTORY_PID, Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.ENGINE_ERROR);
        assertThat(this.registry.getInstance(PID)).isEmpty();
    }

    @Test
    public void successfulEmissionIsForwardedToBoundDispatcher() {
        givenFactoryRegistered(FACTORY_PID);
        givenInstanceCreated(PID, FACTORY_PID);
        this.registry.setEnvelopeDispatcher(this.dispatcher);
        this.registry.setEnvelopeDispatcher(null);
        this.envelope = newEnvelope(PID);

        this.registry.getInstance(PID).orElseThrow().onWireReceive(this.envelope);

        verify(this.dispatcher).dispatch(PID, this.envelope);
    }

    @Test
    public void successfulEmissionWithoutBoundDispatcherIsDropped() {
        givenFactoryRegistered(FACTORY_PID);
        givenInstanceCreated(PID, FACTORY_PID);
        this.envelope = newEnvelope(PID);

        final WireComponent instance = this.registry.getInstance(PID).orElseThrow();

        org.assertj.core.api.Assertions.assertThatCode(() -> instance.onWireReceive(this.envelope))
                .doesNotThrowAnyException();
        verify(this.dispatcher, never()).dispatch(anyString(), any());
    }

    @Test
    public void successfulListenerNotificationOnCreateAndDestroy() {
        final WireComponentRegistryListener listener = mock(WireComponentRegistryListener.class);
        this.registry.addListener(listener);
        this.registry.addListener(listener);
        this.registry.addListener(null);

        givenInstanceCreated(PID, FACTORY_PID);
        this.registry.destroyInstance(PID);

        verify(listener).onInstanceCreated(org.mockito.ArgumentMatchers.eq(PID), org.mockito.ArgumentMatchers.eq(FACTORY_PID),
                any(WireComponent.class));
        verify(listener).onInstanceDestroyed(PID, FACTORY_PID);
    }

    @Test
    public void successfulRemovedListenerStopsNotifications() {
        final WireComponentRegistryListener listener = mock(WireComponentRegistryListener.class);
        this.registry.addListener(listener);
        this.registry.removeListener(listener);
        this.registry.removeListener(null);

        givenInstanceCreated(PID, FACTORY_PID);
        this.registry.destroyInstance(PID);

        verify(listener, never()).onInstanceCreated(anyString(), anyString(), any());
        verify(listener, never()).onInstanceDestroyed(anyString(), anyString());
    }

    private void givenFactoryRegistered(final String factoryPid) {
        if (!FACTORY_PID.equals(factoryPid)) {
            this.registry.registerFactory(new StubComponentFactory(factoryPid, 1, 1, false));
        }
    }

    private void givenInstanceCreated(final String pid, final String factoryPid) {
        this.registry.createInstance(pid, factoryPid, Map.of());
    }

    private void whenCreatingInstance(final String pid, final String factoryPid) {
        this.registry.createInstance(pid, factoryPid, Map.of("key", "value"));
    }

    private void thenInstanceIsTracked(final String pid, final String factoryPid) {
        assertThat(this.registry.getInstance(pid)).isPresent();
        assertThat(this.registry.getInstance(pid).orElseThrow().getPid()).isEqualTo(pid);
        assertThat(this.registry.getFactoryPidOf(pid)).contains(factoryPid);
        assertThat(this.registry.getInstances()).hasSize(1);
        assertThat(this.factory.lastProperties).containsEntry("key", "value");
        assertThat(this.factory.lastEmitContext).isNotNull();
    }

    private static WireEnvelope newEnvelope(final String emitterPid) {
        return new WireEnvelope(emitterPid, List.of(new WireRecord(Map.of())));
    }

    /** 测试用组件工厂桩，可注入创建失败并记录最近一次入参。 */
    private static final class StubComponentFactory implements WireComponentFactory {

        private final String factoryPid;
        private final int defaultInputPortCount;
        private final int defaultOutputPortCount;
        private volatile boolean failOnCreate;
        private volatile Map<String, Object> lastProperties;
        private volatile WireEmitContext lastEmitContext;

        private StubComponentFactory(final String factoryPid, final int defaultInputPortCount,
                final int defaultOutputPortCount, final boolean failOnCreate) {
            this.factoryPid = factoryPid;
            this.defaultInputPortCount = defaultInputPortCount;
            this.defaultOutputPortCount = defaultOutputPortCount;
            this.failOnCreate = failOnCreate;
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
            if (this.failOnCreate) {
                throw new IllegalStateException("create failed");
            }
            this.lastProperties = properties;
            this.lastEmitContext = emitContext;
            return new StubComponent(pid, emitContext);
        }
    }

    /** 测试用组件桩：接收到信封时经注入的发射上下文回发，模拟双向组件。 */
    private static final class StubComponent implements WireComponent {

        private final String pid;
        private final WireEmitContext emitContext;

        private StubComponent(final String pid, final WireEmitContext emitContext) {
            this.pid = pid;
            this.emitContext = emitContext;
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public void onWireReceive(final WireEnvelope envelope) {
            this.emitContext.emit(envelope);
        }
    }
}
