/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.regexfilter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.StringValue;

/**
 * {@link RegexFilterFactory} 的单元测试（Gherkin 风格，正反路径）：
 * 定义（AD id / 类型 / 默认值 / 选项）与上游 metatype 一致，默认端口数 1 入 1 出，
 * create() 返回可用的 RegexFilter。
 */
class RegexFilterFactoryTest {

    private final List<WireEnvelope> emitted = new ArrayList<>();

    private RegexFilterFactory factory;
    private Map<String, Object> properties;
    private WireComponent created;

    @BeforeEach
    void setUp() {
        this.factory = new RegexFilterFactory();
        this.properties = new HashMap<>();
        this.emitted.clear();
        this.created = null;
    }

    @Test
    public void successfulFactoryPidMatchesUpstream() {
        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.wire.RegexFilter");
    }

    @Test
    public void successfulDefaultPortCountsAreOneInputOneOutput() {
        assertThat(this.factory.defaultInputPortCount()).isEqualTo(1);
        assertThat(this.factory.defaultOutputPortCount()).isEqualTo(1);
    }

    @Test
    public void successfulDefinitionMatchesUpstreamMetatype() {
        final Ocd ocd = this.factory.getDefinition();

        assertThat(ocd.getId()).isEqualTo(RegexFilterFactory.FACTORY_PID);
        assertThat(ocd.getAds()).extracting(Ad::getId)
                .containsExactly(RegexFilter.REGEX_PROP, RegexFilter.REGEX_TYPE_PROP);

        final Ad regex = ocd.getAds().get(0);
        assertThat(regex.getType()).isEqualTo(Scalar.STRING);
        assertThat(regex.isRequired()).isFalse();
        assertThat(regex.getDefaultValue()).isEqualTo("");

        final Ad filterType = ocd.getAds().get(1);
        assertThat(filterType.getType()).isEqualTo(Scalar.INTEGER);
        assertThat(filterType.isRequired()).isFalse();
        assertThat(filterType.getDefaultValue()).isEqualTo(1);
        assertThat(filterType.getOptions()).extracting(option -> option.getValue() + ":" + option.getLabel())
                .containsExactly("1:RETAIN", "2:REMOVE");
    }

    @Test
    public void successfulCreateReturnsFunctionalFilter() {
        givenProperties(RegexFilter.REGEX_PROP, "TEMP.*");
        givenProperties(RegexFilter.REGEX_TYPE_PROP, 1);

        whenCreating();

        assertThat(this.created).isInstanceOf(RegexFilter.class);
        assertThat(this.created.getPid()).isEqualTo("filter-1");
        thenEmittedRecordKeys("TEMPERATURE");
    }

    @Test
    public void successfulCreateWithoutFilterPropertyPassesEnvelopeThrough() {
        whenCreating();

        assertThat(this.created).isInstanceOf(RegexFilter.class);
        thenEmittedRecordKeys("TEMPERATURE", "HUMIDITY");
    }

    private void givenProperties(final String key, final Object value) {
        this.properties.put(key, value);
    }

    private void whenCreating() {
        this.created = this.factory.create("filter-1", this.properties, this.emitted::add);
        if (this.created instanceof RegexFilter) {
            this.created.onWireReceive(envelopeWithTwoKeys());
        }
    }

    private void thenEmittedRecordKeys(final String... expectedKeys) {
        assertThat(this.emitted).hasSize(1);
        assertThat(this.emitted.get(0).getRecords()).hasSize(1);
        assertThat(this.emitted.get(0).getRecords().get(0).getProperties()).containsOnlyKeys(expectedKeys);
    }

    private static WireEnvelope envelopeWithTwoKeys() {
        return new WireEnvelope("timer-1", List.of(new WireRecord(Map.of(
                "TEMPERATURE", new IntegerValue(25),
                "HUMIDITY", new StringValue("60%")))));
    }
}
