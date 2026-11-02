/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;
import org.junit.jupiter.api.Test;

/**
 * scale/offset 换算的行为测试：对照上游 {@code BaseAsset.applyScaleAndOffset} 的运算规则
 * （只处理数值型、1/0 跳过、显式类型时先按该类型运算再转回通道类型）。
 * <p>
 * 期望值是独立算出来的数值（如 10 * 2 + 1 = 21），不复用被测代码的公式。
 * </p>
 */
public class ScaleOffsetApplierTest {

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private List<ChannelRecord> records;

    @Test
    public void applyOffsetInValueTypeForDefinedByValueType() {
        givenChannel("ch", DataType.DOUBLE, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 2, 1);
        givenReadRecord("ch", TypedValues.newDoubleValue(10.0d));

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(21.0d);
    }

    @Test
    public void keepIntegerArithmeticWhenValueTypeIsInteger() {
        givenChannel("ch", DataType.INTEGER, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 3, 2);
        givenReadRecord("ch", TypedValues.newIntegerValue(4));

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(14);
    }

    @Test
    public void convertBackToValueTypeWhenScaleOffsetTypeIsExplicit() {
        givenChannel("ch", DataType.INTEGER, ScaleOffsetType.DOUBLE, 2, 1);
        givenReadRecord("ch", TypedValues.newIntegerValue(10));

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(21);
    }

    @Test
    public void skipWhenScaleIsOneAndOffsetIsZero() {
        givenChannel("ch", DataType.DOUBLE, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 1, 0);
        givenReadRecord("ch", TypedValues.newDoubleValue(10.0d));

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(10.0d);
    }

    @Test
    public void leaveNonNumericValuesUntouched() {
        givenChannel("ch", DataType.STRING, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 2, 1);
        givenReadRecord("ch", TypedValues.newStringValue("abc"));

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo("abc");
    }

    @Test
    public void ignoreRecordsWithoutValueOrChannel() {
        givenChannel("ch", DataType.DOUBLE, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 2, 1);
        givenReadRecord("unknown", TypedValues.newDoubleValue(5.0d));
        givenReadRecord("ch", null);

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(5.0d);
        assertThat(this.records.get(1).getValue()).isNull();
    }

    @Test
    public void rejectNumericValueForNonNumericChannel() {
        givenChannel("ch", DataType.STRING, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 2, 1);
        givenReadRecord("ch", TypedValues.newIntegerValue(5));

        assertThatThrownBy(this::whenApplying).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported channel type");
    }

    @Test
    public void keepBooleanValueWhenOffsetWouldChangeIt() {
        givenChannel("ch", DataType.BOOLEAN, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 2, 1);
        givenReadRecord("ch", TypedValues.newBooleanValue(true));

        whenApplying();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(true);
    }

    @Test
    public void reportWhetherScaleAndOffsetApply() {
        givenChannel("ch", DataType.DOUBLE, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 2, 0);
        givenReadRecord("ch", TypedValues.newDoubleValue(1.0d));

        assertThat(ScaleOffsetApplier.shouldApplyScaleAndOffset(this.records.get(0), this.channels.get("ch"))).isTrue();
        assertThat(ScaleOffsetApplier.shouldApplyScaleAndOffset(this.records.get(0), null)).isFalse();
    }

    private void givenChannel(final String name, final DataType valueType, final ScaleOffsetType scaleOffsetType,
            final Number scale, final Number offset) {
        this.channels.put(name, new Channel(name, ChannelType.READ, valueType, scaleOffsetType, scale, offset,
                Map.of()));
    }

    private void givenReadRecord(final String name, final TypedValue<?> value) {
        if (this.records == null) {
            this.records = new ArrayList<>();
        }
        final ChannelRecord record = ChannelRecord.createReadRecord(name, DataType.DOUBLE);
        record.setValue(value);
        this.records.add(record);
    }

    private void whenApplying() {
        ScaleOffsetApplier.apply(this.records, this.channels);
    }
}
