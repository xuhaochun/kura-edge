/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.engine.ScheduledEmitter;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.type.LongValue;

/**
 * {@link Timer} 与 {@link TimerFactory} 的单元测试（Gherkin 风格，正反路径）。
 */
class TimerTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final TimerFactory factory = new TimerFactory();

    private Map<String, Object> properties;
    private boolean periodic;

    private Timer timer;
    private final List<WireEnvelope> emitted = new ArrayList<>();
    private long periodMillis;
    private long initialDelayMillis;
    private Instant nextFireTime;
    private Ocd definition;

    @BeforeEach
    void setUp() {
        this.emitted.clear();
    }

    @Test
    public void successfulTickEmitsTimestampRecord() {
        givenSimpleTimer(10, "SECONDS");

        whenTick();

        thenSingleEnvelopeEmittedWithTimerRecord();
    }

    @Test
    public void successfulPeriodMillisUsesTimeUnitMultiplier() {
        givenSimpleTimer(10, "SECONDS");

        whenGetPeriodMillis();

        thenPeriodMillisIs(10000L);
    }

    @Test
    public void successfulInitialDelayDefaultsToPeriod() {
        givenSimpleTimer(10, "SECONDS");

        whenGetInitialDelayMillis();

        thenInitialDelayMillisIs(10000L);
    }

    @Test
    public void successfulInitialDelayUsesCustomFirstTickInterval() {
        givenProperties(new HashMap<>(Map.of(TimerOptions.PROP_INTERVAL_TYPE, "SIMPLE",
                TimerOptions.PROP_SIMPLE_INTERVAL, 10, TimerOptions.PROP_SIMPLE_TIME_UNIT, "SECONDS",
                TimerOptions.PROP_SIMPLE_TICK_POLICY, "CUSTOM", TimerOptions.PROP_SIMPLE_TICK_CUSTOM_INTERVAL, 5)));
        givenTimerFromProperties();

        whenGetInitialDelayMillis();

        thenInitialDelayMillisIs(5000L);
    }

    @Test
    public void successfulNextCronFireTimeIsComputedAfterBase() {
        givenCronTimer("0/10 * * * * ?");

        whenNextCronFireTime();

        thenNextFireTimeIs(BASE.plusSeconds(10));
    }

    @Test
    public void rejectPeriodMillisWhenTypeIsCron() {
        givenCronTimer("0/10 * * * * ?");

        thenIllegalStateThrownBy(() -> this.timer.getPeriodMillis());
    }

    @Test
    public void rejectInitialDelayWhenTypeIsCron() {
        givenCronTimer("0/10 * * * * ?");

        thenIllegalStateThrownBy(() -> this.timer.getInitialDelayMillis());
    }

    @Test
    public void rejectNextCronFireTimeWhenTypeIsSimple() {
        givenSimpleTimer(10, "SECONDS");

        thenIllegalStateThrownBy(() -> this.timer.nextCronFireTime(BASE));
    }

    @Test
    public void rejectInvalidCronExpression() {
        givenCronTimer("not a cron");

        thenIllegalArgumentThrownBy(() -> this.timer.nextCronFireTime(BASE));
    }

    @Test
    public void rejectInvalidTimeUnit() {
        givenProperties(Map.of(TimerOptions.PROP_INTERVAL_TYPE, "SIMPLE", TimerOptions.PROP_SIMPLE_INTERVAL, 10,
                TimerOptions.PROP_SIMPLE_TIME_UNIT, "WEEKS"));
        givenTimerFromProperties();

        thenIllegalArgumentThrownBy(() -> this.timer.getPeriodMillis());
    }

    @Test
    public void successfulScheduledEmitterReportsSimpleModeAsPeriodic() {
        givenSimpleTimer(5, "SECONDS");
        final ScheduledEmitter emitter = this.timer;

        whenQueryPeriodicMode(emitter);

        thenPeriodicModeIs(true);
        thenEmitterExposesPeriodAndInitialDelay(emitter, 5000L);
    }

    @Test
    public void successfulScheduledEmitterReportsCronModeAsNotPeriodic() {
        givenCronTimer("0/10 * * * * ?");
        final ScheduledEmitter emitter = this.timer;

        whenQueryPeriodicMode(emitter);

        thenPeriodicModeIs(false);
        thenEmitterComputesNextCronFireTime(emitter);
    }

    @Test
    public void successfulFactoryCreatesTimerWithUpstreamPorts() {
        givenProperties(Map.of(TimerOptions.PROP_INTERVAL_TYPE, "SIMPLE", TimerOptions.PROP_SIMPLE_INTERVAL, 10));

        whenFactoryCreate();

        thenComponentCreatedWithPidAndPorts();
    }

    @Test
    public void rejectFactoryCreateWhenSimpleIntervalNotPositive() {
        givenProperties(Map.of(TimerOptions.PROP_INTERVAL_TYPE, "SIMPLE", TimerOptions.PROP_SIMPLE_INTERVAL, 0));

        thenIllegalArgumentThrownBy(() -> this.factory.create("timer-1", this.properties, this.emitted::add));
    }

    @Test
    public void successfulFactoryDefinitionMatchesUpstreamMetatype() {
        whenGetFactoryDefinition();

        thenDefinitionExposesUpstreamAttributes();
    }

    private void givenSimpleTimer(final int interval, final String timeUnit) {
        givenProperties(Map.of(TimerOptions.PROP_INTERVAL_TYPE, "SIMPLE", TimerOptions.PROP_SIMPLE_INTERVAL, interval,
                TimerOptions.PROP_SIMPLE_TIME_UNIT, timeUnit));
        givenTimerFromProperties();
    }

    private void givenCronTimer(final String cronExpression) {
        givenProperties(
                Map.of(TimerOptions.PROP_INTERVAL_TYPE, "CRON", TimerOptions.PROP_CRON_INTERVAL, cronExpression));
        givenTimerFromProperties();
    }

    private void givenProperties(final Map<String, Object> properties) {
        this.properties = properties;
    }

    private void givenTimerFromProperties() {
        this.timer = new Timer("timer-1", new TimerOptions(this.properties), this.emitted::add);
    }

    private void whenQueryPeriodicMode(final ScheduledEmitter emitter) {
        this.periodic = emitter.isPeriodic();
    }

    private void whenTick() {
        this.timer.tick();
    }

    private void whenGetPeriodMillis() {
        this.periodMillis = this.timer.getPeriodMillis();
    }

    private void whenGetInitialDelayMillis() {
        this.initialDelayMillis = this.timer.getInitialDelayMillis();
    }

    private void whenNextCronFireTime() {
        this.nextFireTime = this.timer.nextCronFireTime(BASE);
    }

    private void thenPeriodicModeIs(final boolean expected) {
        assertThat(this.periodic).isEqualTo(expected);
    }

    private void thenEmitterExposesPeriodAndInitialDelay(final ScheduledEmitter emitter, final long expectedPeriod) {
        assertThat(emitter.getPeriodMillis()).isEqualTo(expectedPeriod);
        assertThat(emitter.getInitialDelayMillis()).isEqualTo(expectedPeriod);
    }

    private void thenEmitterComputesNextCronFireTime(final ScheduledEmitter emitter) {
        assertThat(emitter.nextCronFireTime(BASE)).isEqualTo(BASE.plusSeconds(10));
    }

    private void whenFactoryCreate() {
        this.timer = (Timer) this.factory.create("timer-1", this.properties, this.emitted::add);
    }

    private void whenGetFactoryDefinition() {
        this.definition = this.factory.getDefinition();
    }

    private void thenSingleEnvelopeEmittedWithTimerRecord() {
        assertThat(this.emitted).hasSize(1);
        final WireEnvelope envelope = this.emitted.get(0);
        assertThat(envelope.getEmitterPid()).isEqualTo("timer-1");
        assertThat(envelope.getRecords()).hasSize(1);
        final WireRecord record = envelope.getRecords().get(0);
        assertThat(record.getProperties().get(Timer.TIMER_PROP)).isInstanceOf(LongValue.class);
        assertThat(record.getProperties().get(Timer.TIMER_PROP).getValue()).isInstanceOf(Long.class);
    }

    private void thenPeriodMillisIs(final long expected) {
        assertThat(this.periodMillis).isEqualTo(expected);
    }

    private void thenInitialDelayMillisIs(final long expected) {
        assertThat(this.initialDelayMillis).isEqualTo(expected);
    }

    private void thenNextFireTimeIs(final Instant expected) {
        assertThat(this.nextFireTime).isEqualTo(expected);
    }

    private void thenComponentCreatedWithPidAndPorts() {
        assertThat(this.timer).isNotNull();
        assertThat(this.timer.getPid()).isEqualTo("timer-1");
        assertThat(this.factory.defaultInputPortCount()).isZero();
        assertThat(this.factory.defaultOutputPortCount()).isEqualTo(1);
    }

    private void thenDefinitionExposesUpstreamAttributes() {
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.Timer");
        assertThat(this.definition.getName()).isEqualTo("Timer");
        assertThat(this.definition.getAds()).extracting(Ad::getId)
                .containsExactly(TimerOptions.PROP_INTERVAL_TYPE, TimerOptions.PROP_SIMPLE_INTERVAL,
                        TimerOptions.PROP_SIMPLE_TIME_UNIT, TimerOptions.PROP_SIMPLE_TICK_POLICY,
                        TimerOptions.PROP_SIMPLE_TICK_CUSTOM_INTERVAL, TimerOptions.PROP_CRON_INTERVAL);
    }

    private void thenIllegalStateThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class);
    }

    private void thenIllegalArgumentThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }
}
