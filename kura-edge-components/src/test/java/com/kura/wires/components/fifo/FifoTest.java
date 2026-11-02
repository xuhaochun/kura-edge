/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.fifo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.engine.DrainableComponent;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.type.StringValue;

/**
 * {@link Fifo} 与 {@link FifoFactory} 的单元测试（Gherkin 风格，正反路径）。
 */
class FifoTest {

    private final FifoFactory factory = new FifoFactory();

    private final List<WireEnvelope> emitted = new ArrayList<>();
    private final List<WireEnvelope> received = new ArrayList<>();

    private Fifo fifo;

    @BeforeEach
    void setUp() {
        this.emitted.clear();
        this.received.clear();
    }

    @Test
    public void successfulDrainEmitsEnvelopesInArrivalOrder() {
        givenFifo(10, false);
        givenEnvelopes("e1", "e2", "e3");

        whenReceiveAll();
        whenDrain();

        thenEmittedEmitterPidsAreExactly("e1", "e2", "e3");
        thenQueueIsEmpty();
    }

    @Test
    public void successfulFifoIsDrainableByTheEngine() {
        givenFifo(10, false);
        givenEnvelopes("e1");

        whenReceiveAll();
        whenEngineDrain();

        thenEmittedEmitterPidsAreExactly("e1");
    }

    @Test
    public void successfulNoEmissionBeforeDrain() {
        givenFifo(10, false);
        givenEnvelopes("e1", "e2");

        whenReceiveAll();

        thenEmittedCountIs(0);
        thenQueuedCountIs(2);
    }

    @Test
    public void successfulDiscardNewestWhenQueueFullAndDiscardEnabled() {
        givenFifo(2, true);
        givenEnvelopes("e1", "e2", "e3");

        whenReceiveAll();

        thenEmittedCountIs(0);
        thenQueuedCountIs(2);
    }

    @Test
    public void successfulEmitOldestWhenQueueFullWithoutDiscard() {
        givenFifo(2, false);
        givenEnvelopes("e1", "e2", "e3");

        whenReceiveAll();

        thenEmittedEmitterPidsAreExactly("e1");
        thenQueuedCountIs(2);

        whenDrain();

        thenEmittedEmitterPidsAreExactly("e1", "e2", "e3");
    }

    @Test
    public void successfulUpdateChangesCapacity() {
        givenFifo(1, false);
        givenEnvelopes("e1", "e2");

        whenReceiveAll();
        thenEmittedEmitterPidsAreExactly("e1");

        whenUpdatedToCapacity(10);
        givenEnvelopes("e3");
        whenReceiveAll();

        thenEmittedEmitterPidsAreExactly("e1");
        thenQueuedCountIs(2);
    }

    @Test
    public void rejectNullEnvelope() {
        givenFifo(10, false);

        thenNullPointerThrownBy(() -> this.fifo.onWireReceive(null));
    }

    @Test
    public void successfulFactoryCreatesFifoWithUpstreamPorts() {
        whenFactoryCreate();

        thenCreatedFifoExposesPidAndPorts();
    }

    @Test
    public void rejectFactoryCreateWhenCapacityNotPositive() {
        thenIllegalArgumentThrownBy(
                () -> this.factory.create("fifo-1", Map.of(Fifo.PROP_QUEUE_CAPACITY, 0), this.emitted::add));
    }

    @Test
    public void successfulFactoryDefinitionMatchesUpstreamMetatype() {
        final var definition = this.factory.getDefinition();

        thenDefinitionExposesUpstreamAttributes(definition);
    }

    private void givenFifo(final int capacity, final boolean discard) {
        this.fifo = new Fifo("fifo-1",
                Map.of(Fifo.PROP_QUEUE_CAPACITY, capacity, Fifo.PROP_DISCARD_ENVELOPES, discard), this.emitted::add);
    }

    private void givenEnvelopes(final String... emitterPids) {
        for (final String emitterPid : emitterPids) {
            final WireRecord record = new WireRecord(Map.of("SOURCE", new StringValue(emitterPid)));
            this.received.add(new WireEnvelope(emitterPid, List.of(record)));
        }
    }

    private void whenReceiveAll() {
        for (final WireEnvelope received : this.received) {
            this.fifo.onWireReceive(received);
        }
        this.received.clear();
    }

    private void whenEngineDrain() {
        // 引擎只通过 DrainableComponent 契约触发排空，不依赖具体实现类
        final DrainableComponent drainable = this.fifo;
        drainable.drain();
    }

    private void whenDrain() {
        this.fifo.drain();
    }

    private void whenUpdatedToCapacity(final int capacity) {
        this.fifo.updated(Map.of(Fifo.PROP_QUEUE_CAPACITY, capacity, Fifo.PROP_DISCARD_ENVELOPES, false));
    }

    private void whenFactoryCreate() {
        this.fifo = (Fifo) this.factory.create("fifo-1",
                Map.of(Fifo.PROP_QUEUE_CAPACITY, 50, Fifo.PROP_DISCARD_ENVELOPES, false), this.emitted::add);
    }

    private void thenEmittedCountIs(final int expected) {
        assertThat(this.emitted).hasSize(expected);
    }

    private void thenEmittedEmitterPidsAreExactly(final String... emitterPids) {
        assertThat(this.emitted).extracting(WireEnvelope::getEmitterPid).containsExactly(emitterPids);
    }

    private void thenQueuedCountIs(final int expected) {
        assertThat(this.fifo.getQueuedCount()).isEqualTo(expected);
    }

    private void thenQueueIsEmpty() {
        assertThat(this.fifo.getQueuedCount()).isZero();
    }

    private void thenCreatedFifoExposesPidAndPorts() {
        assertThat(this.fifo.getPid()).isEqualTo("fifo-1");
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.Fifo");
        assertThat(this.factory.defaultInputPortCount()).isEqualTo(1);
        assertThat(this.factory.defaultOutputPortCount()).isEqualTo(1);
    }

    private void thenDefinitionExposesUpstreamAttributes(final com.kura.wires.model.definition.Ocd definition) {
        assertThat(definition.getName()).isEqualTo("Fifo");
        assertThat(definition.getAds()).extracting(Ad::getId)
                .containsExactly(Fifo.PROP_QUEUE_CAPACITY, Fifo.PROP_DISCARD_ENVELOPES);
    }

    private void thenNullPointerThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NullPointerException.class);
    }

    private void thenIllegalArgumentThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }
}
