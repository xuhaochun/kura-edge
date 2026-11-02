/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;
import org.junit.jupiter.api.Test;

/**
 * 通道与通道记录的行为测试：通道参数默认值、有效读写类型、记录工厂与状态语义。
 */
public class ChannelRecordTest {

    private Channel channel;
    private Map<String, Object> configuration;
    private ChannelRecord record;

    @Test
    public void useEffectiveDataTypeFromExplicitScaleOffsetType() {
        givenChannelWithScaleOffsetType(ScaleOffsetType.INTEGER, DataType.DOUBLE);

        whenCreatingReadRecord();

        thenRecordValueTypeIs(DataType.INTEGER);
    }

    @Test
    public void useValueTypeWhenScaleOffsetTypeIsDefinedByValueType() {
        givenChannelWithScaleOffsetType(ScaleOffsetType.DEFINED_BY_VALUE_TYPE, DataType.DOUBLE);

        whenCreatingReadRecord();

        thenRecordValueTypeIs(DataType.DOUBLE);
    }

    @Test
    public void exposeChannelConfigurationWithoutChannelPrefix() {
        givenChannelWithScaleOffsetType(ScaleOffsetType.DEFINED_BY_VALUE_TYPE, DataType.INTEGER);

        whenCreatingReadRecord();

        thenRecordConfigurationContains("+type", ChannelType.READ_WRITE.name());
    }

    @Test
    public void createWriteRecordWithChannelValueType() {
        givenChannelWithScaleOffsetType(ScaleOffsetType.DEFINED_BY_VALUE_TYPE, DataType.LONG);

        this.record = this.channel.createWriteRecord(TypedValues.newLongValue(5L));

        thenRecordValueTypeIs(DataType.LONG);
        assertThat(this.record.getValue().getValue()).isEqualTo(5L);
    }

    @Test
    public void createStatusRecordCarryingFailureMessage() {
        this.record = ChannelRecord.createStatusRecord("ch", new ChannelStatus(ChannelFlag.FAILURE, "boom", null));

        assertThat(this.record.getChannelStatus().isSuccess()).isFalse();
        assertThat(this.record.getChannelStatus().getExceptionMessage()).isEqualTo("boom");
        assertThat(this.record.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.FAILURE);
    }

    @Test
    public void rejectNullTypedValueInWriteRecord() {
        final TypedValue<?> value = null;

        assertThatThrownBy(() -> ChannelRecord.createWriteRecord("ch", DataType.INTEGER, value))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void parseChannelTypesCaseInsensitively() {
        assertThat(ChannelType.getChannelType("read_write")).isEqualTo(ChannelType.READ_WRITE);
        assertThat(ScaleOffsetType.getScaleOffsetType("integer")).isEqualTo(ScaleOffsetType.INTEGER);
        assertThat(DataType.getDataType("double")).isEqualTo(DataType.DOUBLE);
        assertThatThrownBy(() -> ChannelType.getChannelType("nope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScaleOffsetType.getScaleOffsetType(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataType.getDataType("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void rejectScaleOffsetDataTypeWhenDefinedByValueType() {
        assertThatThrownBy(ScaleOffsetType.DEFINED_BY_VALUE_TYPE::toDataType)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void givenChannelWithScaleOffsetType(final ScaleOffsetType scaleOffsetType, final DataType valueType) {
        this.configuration = new LinkedHashMap<>();
        this.configuration.put("+type", ChannelType.READ_WRITE.name());
        this.configuration.put("+value.type", valueType.name());
        this.channel = new Channel("ch", ChannelType.READ_WRITE, valueType, scaleOffsetType, 1.0d, 0.0d,
                this.configuration);
        this.channel.setEnabled(true);
        this.channel.setUnit("V");
    }

    private void whenCreatingReadRecord() {
        this.record = this.channel.createReadRecord();
    }

    private void thenRecordValueTypeIs(final DataType expected) {
        assertThat(this.record.getValueType()).isEqualTo(expected);
    }

    private void thenRecordConfigurationContains(final String key, final String expected) {
        assertThat(this.record.getChannelConfig()).containsEntry(key, expected);
    }
}
