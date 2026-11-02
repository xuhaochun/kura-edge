/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.regexfilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.TypedValue;

/**
 * {@link RegexFilter} 的单元测试（Gherkin 风格，正反路径）。
 */
class RegexFilterTest {

    private static final String REGEX_PROP = RegexFilter.REGEX_PROP;
    private static final String REGEX_TYPE_PROP = RegexFilter.REGEX_TYPE_PROP;

    private final List<WireEnvelope> emitted = new ArrayList<>();

    private Map<String, Object> properties;
    private RegexFilter filter;
    private WireEnvelope envelope;

    @BeforeEach
    void setUp() {
        this.emitted.clear();
    }

    @Test
    public void successfulRetainMatchingKeys() {
        givenFilter("TEMP.*", 1);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        thenSingleEmittedRecordHasKeys("TEMPERATURE");
    }

    @Test
    public void successfulRemoveMatchingKeys() {
        givenFilter("TEMP.*", 2);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        thenSingleEmittedRecordHasKeys("HUMIDITY");
    }

    /**
     * 上游短路语义：REMOVE 且**没有任何** key 命中正则时，上游 {@code match} 直接返回空表
     * （{@code allMatch(matches(pattern, REMOVE))} 与「全部匹配」共用同一分支）。
     * 本用例锁定该行为，避免把移植代码「修正」成与上游不一致。
     */
    @Test
    public void successfulRemoveYieldsEmptyRecordWhenNoKeyMatches() {
        givenFilter("temp.*", 2);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        assertThat(this.emitted).hasSize(1);
        assertThat(this.emitted.get(0).getRecords()).hasSize(1);
        assertThat(this.emitted.get(0).getRecords().get(0).getProperties()).isEmpty();
    }

    @Test
    public void successfulDefaultFilterTypeIsRetain() {
        givenFilter("TEMP.*", null);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        thenSingleEmittedRecordHasKeys("TEMPERATURE");
    }

    @Test
    public void successfulKeepWholeRecordWhenAllKeysMatchRetain() {
        givenFilter(".*", 1);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        thenSingleEmittedRecordHasKeys("TEMPERATURE", "HUMIDITY");
    }

    @Test
    public void successfulPassThroughWhenFilterIsEmpty() {
        givenFilter("", 1);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        thenSingleEmittedRecordHasKeys("TEMPERATURE", "HUMIDITY");
    }

    @Test
    public void successfulEmitUnfilteredRecordsWhenRegexIsInvalid() {
        givenFilter("[", 1);
        givenEnvelopeWithTwoKeys();

        whenOnWireReceive();

        thenSingleEmittedRecordHasKeys("TEMPERATURE", "HUMIDITY");
    }

    @Test
    public void rejectNullEnvelope() {
        givenFilter("TEMP.*", 1);

        thenNullPointerThrownBy(() -> this.filter.onWireReceive(null));
    }

    @Test
    public void successfulFilterTypeResolvesFromUpstreamTypeId() {
        thenFilterTypeIs(FilterType.fromTypeId(1), FilterType.RETAIN);
        thenFilterTypeIs(FilterType.fromTypeId(2), FilterType.REMOVE);
        thenFilterTypeIs(FilterType.fromTypeId(3), FilterType.RETAIN);
    }

    private void givenFilter(final String regex, final Integer typeId) {
        this.properties = new java.util.HashMap<>();
        this.properties.put(REGEX_PROP, regex);
        if (typeId != null) {
            this.properties.put(REGEX_TYPE_PROP, typeId);
        }
        this.filter = new RegexFilter("filter-1", this.properties, this.emitted::add);
    }

    private void givenEnvelopeWithTwoKeys() {
        final Map<String, TypedValue<?>> props = Map.of("TEMPERATURE", new IntegerValue(25), "HUMIDITY",
                new IntegerValue(60));
        this.envelope = new WireEnvelope("timer-1", List.of(new WireRecord(props)));
    }

    private void whenOnWireReceive() {
        this.filter.onWireReceive(this.envelope);
    }

    private void thenSingleEmittedRecordHasKeys(final String... keys) {
        assertThat(this.emitted).hasSize(1);
        assertThat(this.emitted.get(0).getEmitterPid()).isEqualTo("filter-1");
        assertThat(this.emitted.get(0).getRecords()).hasSize(1);
        assertThat(this.emitted.get(0).getRecords().get(0).getProperties()).containsOnlyKeys(keys);
    }

    private void thenFilterTypeIs(final FilterType actual, final FilterType expected) {
        assertThat(actual).isEqualTo(expected);
    }

    private void thenNullPointerThrownBy(final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(NullPointerException.class);
    }
}
