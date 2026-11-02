/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.type.StringValue;

/**
 * {@link CloudSubscriber}、{@link CloudSubscriberFactory} 与 {@link LogSubscriberSource}
 * 的单元测试（Gherkin 风格，正反路径）。
 */
class CloudSubscriberTest {

    private final LogSubscriberSource source = new LogSubscriberSource();
    private final CloudSubscriberFactory factory = new CloudSubscriberFactory(this.source);

    private final List<WireEnvelope> emitted = new ArrayList<>();
    private final List<WireEnvelope> delivered = new ArrayList<>();

    private CloudSubscriber subscriber;
    private WireEnvelope envelope;

    @BeforeEach
    void setUp() {
        this.emitted.clear();
        this.delivered.clear();
    }

    @Test
    public void successfulDeliverEmitsEnvelopeWithOwnPid() {
        givenSubscriberWithProperties();
        givenEnvelopeFromCloud();

        whenDeliver();

        thenSingleEnvelopeEmittedFrom("subscriber-1");
    }

    @Test
    public void rejectNullEnvelope() {
        givenSubscriberWithProperties();

        thenNullPointerThrownBy(() -> this.subscriber.deliver(null));
    }

    @Test
    public void rejectNullPropertiesOnUpdate() {
        givenSubscriberWithProperties();

        thenNullPointerThrownBy(() -> this.subscriber.updated(null));
    }

    @Test
    public void successfulFactoryBindsComponentToSource() {
        givenFactoryCreatedSubscriber();
        givenEnvelopeFromCloud();

        whenSourceDelivers();

        thenSingleEnvelopeEmittedFrom("subscriber-1");
    }

    @Test
    public void successfulLogSourceForwardsWhenBound() {
        this.source.bind(this.delivered::add);
        givenEnvelopeFromCloud();

        whenSourceDelivers();

        thenSourceForwardedEnvelope();
    }

    @Test
    public void successfulLogSourceDropsWhenUnbound() {
        this.source.bind(this.delivered::add);
        this.source.unbind();
        givenEnvelopeFromCloud();

        whenSourceDelivers();

        thenSourceDroppedEnvelope();
    }

    @Test
    public void successfulFactoryDefinitionMatchesUpstreamMetatype() {
        final var definition = this.factory.getDefinition();

        thenDefinitionExposesUpstreamAttributes(definition);
    }

    private void givenSubscriberWithProperties() {
        this.subscriber = new CloudSubscriber("subscriber-1",
                Map.of(CloudSubscriber.PROP_CLOUD_SUBSCRIBER_TARGET, "(kura.service.pid=changeme)"),
                this.emitted::add);
    }

    private void givenFactoryCreatedSubscriber() {
        this.subscriber = (CloudSubscriber) this.factory.create("subscriber-1",
                Map.of(CloudSubscriber.PROP_CLOUD_SUBSCRIBER_TARGET, "(kura.service.pid=changeme)"),
                this.emitted::add);
    }

    private void givenEnvelopeFromCloud() {
        final WireRecord record = new WireRecord(Map.of("COMMAND", new StringValue("REBOOT")));
        this.envelope = new WireEnvelope("cloud-connection", List.of(record));
    }

    private void whenDeliver() {
        this.subscriber.deliver(this.envelope);
    }

    private void whenSourceDelivers() {
        this.source.deliver(this.envelope);
    }

    private void thenSingleEnvelopeEmittedFrom(final String expectedPid) {
        assertThat(this.emitted).hasSize(1);
        assertThat(this.emitted.get(0).getEmitterPid()).isEqualTo(expectedPid);
        assertThat(this.emitted.get(0).getRecords()).isEqualTo(this.envelope.getRecords());
    }

    private void thenSourceForwardedEnvelope() {
        assertThat(this.delivered).containsExactly(this.envelope);
    }

    private void thenSourceDroppedEnvelope() {
        assertThat(this.delivered).isEmpty();
    }

    private void thenDefinitionExposesUpstreamAttributes(final com.kura.wires.model.definition.Ocd definition) {
        assertThat(definition.getName()).isEqualTo("Subscriber");
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.CloudSubscriber");
        assertThat(this.factory.defaultInputPortCount()).isZero();
        assertThat(this.factory.defaultOutputPortCount()).isEqualTo(1);
        assertThat(definition.getAds()).extracting(Ad::getId)
                .containsExactly(CloudSubscriber.PROP_CLOUD_SUBSCRIBER_TARGET);
    }

    private void thenNullPointerThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NullPointerException.class);
    }
}
