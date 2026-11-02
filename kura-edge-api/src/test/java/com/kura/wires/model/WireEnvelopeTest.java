package com.kura.wires.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.StringValue;

/**
 * WireRecord / WireEnvelope 不可变语义验证（对照上游 @Immutable 约定）。
 */
class WireEnvelopeTest {

    @Test
    void rejectNullArguments() {
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new WireRecord(null));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new WireEnvelope(null, List.of()));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new WireEnvelope("pid", null));
    }

    @Test
    void keepRecordImmutableAgainstExternalMapChanges() {
        final Map<String, com.kura.wires.model.type.TypedValue<?>> props = new HashMap<>();
        props.put("temperature", new IntegerValue(20));
        final WireRecord record = new WireRecord(props);
        props.put("extra", new StringValue("x"));
        assertThat(record.getProperties()).containsOnlyKeys("temperature");
        // 不可变 Map：写入抛 UnsupportedOperationException
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> record.getProperties().put("other", new StringValue("y")));
    }

    @Test
    void keepEnvelopeImmutableAgainstExternalListChanges() {
        final List<WireRecord> records = new ArrayList<>();
        records.add(new WireRecord(Map.of()));
        final WireEnvelope envelope = new WireEnvelope("emitter", records);
        records.clear();
        assertThat(envelope.getRecords()).hasSize(1);
        assertThat(envelope.getEmitterPid()).isEqualTo("emitter");
        assertThat(envelope.getScope()).isEqualTo("WIRES");
        assertThat(envelope.getTimestamp()).isPositive();
    }

    @Test
    void preserveExplicitTimestampAndScope() {
        final WireEnvelope envelope = new WireEnvelope(123L, "e", null, List.of(new WireRecord(Map.of())));
        assertThat(envelope.getTimestamp()).isEqualTo(123L);
        // null scope 回退为 WIRES
        assertThat(envelope.getScope()).isEqualTo("WIRES");
    }
}
