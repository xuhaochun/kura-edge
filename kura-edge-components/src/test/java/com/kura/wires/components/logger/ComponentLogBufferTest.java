/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ComponentLogBuffer} 单元测试（Gherkin 风格，正反路径）：100 行环形语义、
 * 增量序号、序号倒退对齐、按 PID 隔离。
 */
class ComponentLogBufferTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final ComponentLogBuffer buffer = new ComponentLogBuffer(Clock.fixed(NOW, ZoneOffset.UTC));

    private List<ComponentLogLine> result;

    @Test
    public void successfulAppendKeepsOnlyLatestHundredLines() {
        givenLinesAppended("logger-1", 105);

        whenTail("logger-1", 0L, 500);

        thenLineCountIs(ComponentLogBuffer.CAPACITY);
        thenFirstSeqIs(6L);
        thenLastSeqIs(105L);
    }

    @Test
    public void successfulTailReturnsOnlyLinesNewerThanSince() {
        givenLinesAppended("logger-1", 3);

        whenTail("logger-1", 1L, 100);

        thenLineCountIs(2);
        thenFirstSeqIs(2L);
    }

    @Test
    public void successfulTailRewindsWhenSinceIsAheadOfBuffer() {
        givenLinesAppended("logger-1", 3);

        whenTail("logger-1", 999L, 100);

        thenLineCountIs(3);
        thenFirstSeqIs(1L);
    }

    @Test
    public void successfulTailHonoursLimitKeepingNewestLines() {
        givenLinesAppended("logger-1", 5);

        whenTail("logger-1", 0L, 2);

        thenLineCountIs(2);
        thenFirstSeqIs(4L);
        thenLastSeqIs(5L);
    }

    @Test
    public void successfulClearEmptiesOnlyThatComponent() {
        givenLinesAppended("logger-1", 2);
        givenLinesAppended("logger-2", 1);

        this.buffer.clear("logger-1");
        whenTail("logger-1", 0L, 100);

        thenLineCountIs(0);
        assertThat(this.buffer.lastSeq("logger-2")).isEqualTo(3L);
    }

    @Test
    public void successfulPidsAreIsolated() {
        givenLinesAppended("logger-1", 2);
        givenLinesAppended("logger-2", 1);

        whenTail("logger-2", 0L, 100);

        thenLineCountIs(1);
        assertThat(this.result.get(0).text()).isEqualTo("line-1");
    }

    @Test
    public void successfulSequenceIsMonotonicAcrossPids() {
        givenLinesAppended("logger-1", 1);
        givenLinesAppended("logger-2", 1);

        assertThat(this.buffer.lastSeq("logger-1")).isEqualTo(1L);
        assertThat(this.buffer.lastSeq("logger-2")).isEqualTo(2L);
    }

    @Test
    public void successfulTimestampComesFromInjectedClock() {
        givenLinesAppended("logger-1", 1);

        whenTail("logger-1", 0L, 100);

        assertThat(this.result.get(0).timestamp()).isEqualTo(NOW);
    }

    @Test
    public void rejectNullPid() {
        assertThatThrownBy(() -> this.buffer.append(null, "text")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.buffer.append("pid", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.buffer.tail(null, 0L, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.buffer.lastSeq(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.buffer.clear(null)).isInstanceOf(NullPointerException.class);
    }

    private void givenLinesAppended(final String pid, final int count) {
        for (int index = 1; index <= count; index++) {
            this.buffer.append(pid, "line-" + index);
        }
    }

    private void whenTail(final String pid, final long sinceSeq, final int limit) {
        this.result = this.buffer.tail(pid, sinceSeq, limit);
    }

    private void thenLineCountIs(final int expected) {
        assertThat(this.result).hasSize(expected);
    }

    private void thenFirstSeqIs(final long expected) {
        assertThat(this.result.get(0).seq()).isEqualTo(expected);
    }

    private void thenLastSeqIs(final long expected) {
        assertThat(this.result.get(this.result.size() - 1).seq()).isEqualTo(expected);
    }
}
