/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.StringValue;

/**
 * {@link Logger} 与 {@link LoggerFactory} 的单元测试（Gherkin 风格，正反路径），
 * 用 Logback ListAppender 捕获组件日志断言行为。
 */
class LoggerTest {

    private final ComponentLogBuffer logBuffer = new ComponentLogBuffer();

    private final LoggerFactory factory = new LoggerFactory(this.logBuffer);

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;
    private Map<String, Object> properties;
    private WireEnvelope envelope;
    private String joinedLog;

    @BeforeEach
    void setUp() {
        final ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Logger.class);
        this.logAppender = new ListAppender<>();
        this.logAppender.start();
        logbackLogger.addAppender(this.logAppender);
    }

    @AfterEach
    void tearDown() {
        final ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Logger.class);
        logbackLogger.detachAppender(this.logAppender);
    }

    @Test
    public void successfulQuietLogRecordsEmitterPidOnly() {
        givenQuietLogger();
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenLogContainsEmitterPidOnly();
    }

    @Test
    public void successfulVerboseLogListsAllRecordEntries() {
        givenVerboseLogger();
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenLogContainsEveryRecordEntry();
    }

    @Test
    public void successfulUpdateSwitchesVerbosityToVerbose() {
        givenQuietLogger();
        givenEnvelopeFromTimer();

        whenUpdatedToVerbose();
        whenOnWireReceive();

        thenLogContainsEveryRecordEntry();
    }

    @Test
    public void successfulQuietDeliveryWritesSingleLineToLogBuffer() {
        givenQuietLogger();
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenBufferContainsOnlyEmitterPid();
    }

    @Test
    public void successfulVerboseDeliveryWritesRecordEntriesToLogBuffer() {
        givenVerboseLogger();
        givenEnvelopeFromTimer();

        whenOnWireReceive();

        thenBufferContainsEveryRecordEntry();
    }

    @Test
    public void successfulFactoryCreatedLoggerSharesBuffer() {
        givenQuietLogger();
        givenEnvelopeFromTimer();

        whenFactoryCreate();
        whenOnWireReceive();

        thenBufferContainsOnlyEmitterPid();
    }

    @Test
    public void rejectNullEnvelope() {
        givenQuietLogger();

        thenNullPointerThrownBy(() -> this.logger.onWireReceive(null));
    }

    @Test
    public void rejectNullPropertiesOnUpdate() {
        givenQuietLogger();

        thenNullPointerThrownBy(() -> this.logger.updated(null));
    }

    @Test
    public void successfulFactoryCreatesLoggerWithPid() {
        givenQuietLogger();

        whenFactoryCreate();

        thenCreatedLoggerExposesPid();
    }

    @Test
    public void successfulFactoryDefinitionMatchesUpstreamMetatype() {
        whenGetFactoryDefinition();

        thenDefinitionExposesUpstreamAttributes();
    }

    private void givenQuietLogger() {
        givenProperties(new HashMap<>(Map.of(Logger.PROP_LOG_LEVEL, LoggingVerbosity.QUIET.name())));
        this.logger = new Logger("logger-1", this.properties, this.logBuffer);
    }

    private void givenVerboseLogger() {
        givenProperties(new HashMap<>(Map.of(Logger.PROP_LOG_LEVEL, LoggingVerbosity.VERBOSE.name())));
        this.logger = new Logger("logger-1", this.properties, this.logBuffer);
    }

    private void givenProperties(final Map<String, Object> properties) {
        this.properties = properties;
    }

    private void thenBufferContainsOnlyEmitterPid() {
        final List<ComponentLogLine> lines = this.logBuffer.tail("logger-1", 0L, ComponentLogBuffer.CAPACITY);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).text()).contains("timer-1").doesNotContain("TEMP");
    }

    private void thenBufferContainsEveryRecordEntry() {
        final List<ComponentLogLine> lines = this.logBuffer.tail("logger-1", 0L, ComponentLogBuffer.CAPACITY);
        final String joined = lines.stream().map(ComponentLogLine::text).reduce("", (left, right) -> left + " | " + right);
        assertThat(joined).contains("TEMP").contains("25").contains("NAME").contains("edge");
    }

    private void givenEnvelopeFromTimer() {
        final WireRecord record = new WireRecord(Map.of("TEMP", new IntegerValue(25), "NAME", new StringValue("edge")));
        this.envelope = new WireEnvelope("timer-1", List.of(record));
    }

    private void whenOnWireReceive() {
        this.logger.onWireReceive(this.envelope);
        this.joinedLog = joinedLog();
    }

    private void whenUpdatedToVerbose() {
        this.properties.put(Logger.PROP_LOG_LEVEL, LoggingVerbosity.VERBOSE.name());
        this.logger.updated(this.properties);
    }

    private void whenFactoryCreate() {
        this.logger = (Logger) this.factory.create("logger-1", this.properties, envelope -> {
            // Logger 不发射
        });
    }

    private void whenGetFactoryDefinition() {
        this.factory.getDefinition();
    }

    private void thenLogContainsEmitterPidOnly() {
        assertThat(this.logAppender.list).isNotEmpty();
        assertThat(this.logAppender.list).allMatch(event -> event.getLevel() == Level.INFO);
        assertThat(this.joinedLog).contains("timer-1");
        assertThat(this.joinedLog).doesNotContain("TEMP").doesNotContain("edge");
    }

    private void thenLogContainsEveryRecordEntry() {
        assertThat(this.joinedLog).contains("timer-1");
        assertThat(this.joinedLog).contains("TEMP").contains("25");
        assertThat(this.joinedLog).contains("NAME").contains("edge");
    }

    private void thenCreatedLoggerExposesPid() {
        assertThat(this.logger.getPid()).isEqualTo("logger-1");
    }

    private void thenDefinitionExposesUpstreamAttributes() {
        final var definition = this.factory.getDefinition();
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.Logger");
        assertThat(definition.getName()).isEqualTo("Logger");
        assertThat(this.factory.defaultInputPortCount()).isEqualTo(1);
        assertThat(this.factory.defaultOutputPortCount()).isZero();
        assertThat(definition.getAds()).hasSize(1);
        final Ad logVerbosity = definition.getAds().get(0);
        assertThat(logVerbosity.getId()).isEqualTo(Logger.PROP_LOG_LEVEL);
        assertThat(logVerbosity.getDefaultValue()).isEqualTo("QUIET");
        assertThat(logVerbosity.getOptions()).extracting(com.kura.wires.model.definition.AdOption::getValue)
                .containsExactly("VERBOSE", "QUIET");
    }

    private void thenNullPointerThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NullPointerException.class);
    }

    private String joinedLog() {
        final List<String> messages = new ArrayList<>();
        for (final ILoggingEvent event : this.logAppender.list) {
            messages.add(event.getFormattedMessage());
        }
        return String.join(" | ", messages);
    }
}
