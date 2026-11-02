/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.StringValue;

/**
 * {@link DbWireRecordStore} 与 {@link DbStoreFactory} 的单元测试（Gherkin 风格，正反
 * 路径），经 {@code @DataJpaTest} + H2 内存库验证真实落库。
 */
@DataJpaTest
class DbWireRecordStoreTest {

    private static final long RECEIVED_AT_MILLIS = 1700000000000L;

    @Autowired
    private WireRecordRepository repository;

    private final List<WireEnvelope> emitted = new ArrayList<>();
    private final List<WireEnvelope> received = new ArrayList<>();

    private DbStoreFactory factory;
    private DbWireRecordStore store;
    private Map<String, Object> properties;
    private WireEnvelope envelope;

    @BeforeEach
    void setUp() {
        this.emitted.clear();
        this.received.clear();
        this.factory = new DbStoreFactory(this.repository);
    }

    @Test
    public void successfulStoreOnEnvelopeReceived() {
        givenStore(10000, 5000);
        givenEnvelope("timer-1");

        whenReceiveAll();

        thenRecordPersistedFor("timer-1");
    }

    @Test
    public void successfulForwardAfterStore() {
        givenStore(10000, 5000);
        givenEnvelope("timer-1");

        whenReceiveAll();

        thenSingleEnvelopeForwardedFrom("db-1");
    }

    @Test
    public void successfulCleanupKeepsNewestRecordsWhenTableSizeReached() {
        givenStore(2, 1);
        givenEnvelope("e1");
        givenEnvelope("e2");
        givenEnvelope("e3");

        whenReceiveAll();

        thenPersistedEmitterPidsAreExactly("e2", "e3");
    }

    @Test
    public void successfulDeleteAllRecordsWhenCleanupKeepZero() {
        givenStore(1, 0);
        givenEnvelope("e1");
        givenEnvelope("e2");

        whenReceiveAll();

        thenPersistedEmitterPidsAreExactly("e2");
    }

    @Test
    public void rejectNullEnvelope() {
        givenStore(10000, 5000);

        thenNullPointerThrownBy(() -> this.store.onWireReceive(null));
    }

    @Test
    public void rejectNullPropertiesOnUpdate() {
        givenStore(10000, 5000);

        thenNullPointerThrownBy(() -> this.store.updated(null));
    }

    @Test
    public void successfulFactoryCreatesStoreWiredWithRepository() {
        givenFactoryProperties(10, 5);

        whenFactoryCreate();
        givenEnvelope("timer-1");
        whenReceiveAll();

        thenRecordPersistedFor("timer-1");
    }

    @Test
    public void rejectFactoryCreateWhenMaximumTableSizeBelowOne() {
        givenFactoryProperties(0, 5);

        thenIllegalArgumentThrownBy(() -> this.factory.create("db-1", this.properties, this.emitted::add));
    }

    @Test
    public void successfulFactoryDefinitionMatchesUpstreamMetatype() {
        final var definition = this.factory.getDefinition();

        thenDefinitionExposesUpstreamAttributes(definition);
    }

    private void givenStore(final int maximumTableSize, final int cleanupRecordsKeep) {
        this.properties = new HashMap<>(Map.of(DbWireRecordStore.PROP_MAXIMUM_TABLE_SIZE, maximumTableSize,
                DbWireRecordStore.PROP_CLEANUP_RECORDS_KEEP, cleanupRecordsKeep));
        this.store = new DbWireRecordStore("db-1", this.properties, this.emitted::add, this.repository);
    }

    private void givenFactoryProperties(final int maximumTableSize, final int cleanupRecordsKeep) {
        this.properties = new HashMap<>(Map.of(DbWireRecordStore.PROP_MAXIMUM_TABLE_SIZE, maximumTableSize,
                DbWireRecordStore.PROP_CLEANUP_RECORDS_KEEP, cleanupRecordsKeep));
    }

    private void givenEnvelope(final String emitterPid) {
        final WireRecord record = new WireRecord(Map.of("TEMP", new IntegerValue(25), "NAME", new StringValue("edge")));
        this.received.add(new WireEnvelope(RECEIVED_AT_MILLIS, emitterPid, "WIRES", List.of(record)));
    }

    private void whenReceiveAll() {
        for (final WireEnvelope received : this.received) {
            this.store.onWireReceive(received);
        }
        this.received.clear();
    }

    private void whenFactoryCreate() {
        this.store = (DbWireRecordStore) this.factory.create("db-1", this.properties, this.emitted::add);
    }

    private void thenRecordPersistedFor(final String emitterPid) {
        final List<WireRecordEntity> stored = this.repository.findAll();
        assertThat(stored).hasSize(1);
        final WireRecordEntity entity = stored.get(0);
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getEmitterPid()).isEqualTo(emitterPid);
        assertThat(entity.getReceivedAt()).isEqualTo(Instant.ofEpochMilli(RECEIVED_AT_MILLIS));
        assertThat(entity.getPropValue()).contains("TEMP").contains("25").contains("NAME").contains("edge");
    }

    private void thenSingleEnvelopeForwardedFrom(final String expectedPid) {
        assertThat(this.emitted).hasSize(1);
        assertThat(this.emitted.get(0).getEmitterPid()).isEqualTo(expectedPid);
        assertThat(this.emitted.get(0).getRecords()).hasSize(1);
    }

    private void thenPersistedEmitterPidsAreExactly(final String... emitterPids) {
        final List<WireRecordEntity> stored = this.repository.findAll();
        assertThat(stored).hasSize(emitterPids.length);
        assertThat(stored).extracting(WireRecordEntity::getEmitterPid).containsExactly(emitterPids);
    }

    private void thenDefinitionExposesUpstreamAttributes(final com.kura.wires.model.definition.Ocd definition) {
        assertThat(definition.getName()).isEqualTo("DB Store");
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.DbWireRecordStore");
        assertThat(this.factory.defaultInputPortCount()).isEqualTo(1);
        assertThat(this.factory.defaultOutputPortCount()).isEqualTo(1);
        assertThat(definition.getAds()).extracting(Ad::getId)
                .containsExactly(DbWireRecordStore.PROP_MAXIMUM_TABLE_SIZE,
                        DbWireRecordStore.PROP_CLEANUP_RECORDS_KEEP);
    }

    private void thenNullPointerThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NullPointerException.class);
    }

    private void thenIllegalArgumentThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }
}
