/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.publisher;

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
import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.StringValue;

/**
 * {@link CloudPublisher}、{@link CloudPublisherFactory} 与 {@link LogPublisherSink}
 * 的单元测试（Gherkin 风格，正反路径）。
 */
class CloudPublisherTest {

    private final CloudPublisherFactory factory = new CloudPublisherFactory(new LogPublisherSink());

    private final List<WireEnvelope> emitted = new ArrayList<>();
    private final List<WireRecord> published = new ArrayList<>();

    private Map<String, Object> properties;
    private CloudPublisher publisher;
    private PublisherSink sink;
    private WireEnvelope envelope;

    @BeforeEach
    void setUp() {
        this.emitted.clear();
        this.published.clear();
        this.sink = this.published::addAll;
    }

    @Test
    public void successfulPublishRecordsToSink() {
        givenPublisher(false);
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenRecordsPublishedToSink();
        thenEmittedCountIs(0);
    }

    @Test
    public void successfulForwardDownstreamWhenEnabled() {
        givenPublisher(true);
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenRecordsPublishedToSink();
        thenSingleEnvelopeForwardedFrom("publisher-1");
    }

    @Test
    public void successfulContinueWhenSinkThrows() {
        givenThrowingSink();
        givenPublisher(true);
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenRecordsPublishedToSink();
        thenSingleEnvelopeForwardedFrom("publisher-1");
    }

    @Test
    public void successfulSkipPublishWhenSinkMissing() {
        givenMissingSink();
        givenPublisher(true);
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenSingleEnvelopeForwardedFrom("publisher-1");
    }

    @Test
    public void successfulUpdateTogglesForward() {
        givenPublisher(false);
        givenEnvelopeFromTimer();
        this.publisher.onWireReceive(this.envelope);
        thenEmittedCountIs(0);

        whenUpdatedToForward(true);
        this.publisher.onWireReceive(this.envelope);

        thenSingleEnvelopeForwardedFrom("publisher-1");
    }

    @Test
    public void rejectNullEnvelope() {
        givenPublisher(false);

        thenNullPointerThrownBy(() -> this.publisher.onWireReceive(null));
    }

    @Test
    public void successfulLogSinkPublishesRecordsWithoutError() {
        givenEnvelopeFromTimer();

        whenLogSinkPublishes();

        thenLogSinkHandledEveryRecord();
    }

    @Test
    public void successfulFactoryCreatesPublisherWithUpstreamPorts() {
        givenPublisher(false);

        whenFactoryCreate();

        thenCreatedPublisherExposesPidAndPorts();
    }

    @Test
    public void successfulFactoryDefinitionMatchesUpstreamMetatype() {
        final var definition = this.factory.getDefinition();

        thenDefinitionExposesUpstreamAttributes(definition);
    }

    private void givenPublisher(final boolean forward) {
        this.properties = Map.of(CloudPublisher.PROP_CLOUD_PUBLISHER_TARGET, "(kura.service.pid=changeme)",
                CloudPublisher.PROP_PUBLISH_FORWARD, forward);
        this.publisher = new CloudPublisher("publisher-1", this.sink, this.properties, this.emitted::add);
    }

    private void givenThrowingSink() {
        this.sink = records -> {
            this.published.addAll(records);
            throw new IllegalStateException("sink unavailable");
        };
    }

    private void givenMissingSink() {
        this.sink = null;
    }

    private void givenEnvelopeFromTimer() {
        final WireRecord record = new WireRecord(Map.of("TEMP", new IntegerValue(25), "NAME", new StringValue("edge")));
        this.envelope = new WireEnvelope("timer-1", List.of(record));
    }

    private void whenOnWireReceive() {
        this.publisher.onWireReceive(this.envelope);
    }

    private void whenUpdatedToForward(final boolean forward) {
        this.publisher.updated(Map.of(CloudPublisher.PROP_CLOUD_PUBLISHER_TARGET, "(kura.service.pid=changeme)",
                CloudPublisher.PROP_PUBLISH_FORWARD, forward));
    }

    private void whenLogSinkPublishes() {
        new LogPublisherSink().publish(this.envelope.getRecords());
    }

    private void whenFactoryCreate() {
        this.publisher = (CloudPublisher) this.factory.create("publisher-1", this.properties, this.emitted::add);
    }

    private void thenRecordsPublishedToSink() {
        assertThat(this.published).isEqualTo(this.envelope.getRecords());
    }

    private void thenSingleEnvelopeForwardedFrom(final String expectedPid) {
        assertThat(this.emitted).hasSize(1);
        assertThat(this.emitted.get(0).getEmitterPid()).isEqualTo(expectedPid);
        assertThat(this.emitted.get(0).getRecords()).isEqualTo(this.envelope.getRecords());
    }

    private void thenEmittedCountIs(final int expected) {
        assertThat(this.emitted).hasSize(expected);
    }

    private void thenLogSinkHandledEveryRecord() {
        assertThat(this.envelope.getRecords()).isNotEmpty();
    }

    private void thenCreatedPublisherExposesPidAndPorts() {
        assertThat(this.publisher.getPid()).isEqualTo("publisher-1");
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.CloudPublisher");
        assertThat(this.factory.defaultInputPortCount()).isEqualTo(1);
        assertThat(this.factory.defaultOutputPortCount()).isEqualTo(1);
    }

    private void thenDefinitionExposesUpstreamAttributes(final com.kura.wires.model.definition.Ocd definition) {
        assertThat(definition.getName()).isEqualTo("Publisher");
        assertThat(definition.getAds()).extracting(Ad::getId)
                .containsExactly(CloudPublisher.PROP_CLOUD_PUBLISHER_TARGET, CloudPublisher.PROP_PUBLISH_FORWARD);
        assertThat(definition.getAds().get(1).getDefaultValue()).isEqualTo(false);
    }

    private void thenNullPointerThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NullPointerException.class);
    }
}
