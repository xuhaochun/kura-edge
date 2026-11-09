/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kura.wires.model.type.DataType;

/** 通道配置解析：默认值、显式值、数量推导与非法组合。 */
class ModbusChannelConfigTest {

    @Test
    public void useHoldingRegisterDefaults() {
        final ModbusChannelConfig config = ModbusChannelConfig.from(Map.of(), DataType.INTEGER);

        assertThat(config.getObjectType()).isEqualTo(ModbusChannelConfig.ObjectType.HOLDING_REGISTER);
        assertThat(config.getAddress()).isZero();
        assertThat(config.getRegisterCount()).isEqualTo(1);
        assertThat(config.getDataOrder()).isEqualTo(ModbusDataOrder.BIG_ENDIAN);
        assertThat(config.getObjectType().isWritable()).isTrue();
    }

    @Test
    public void parseExplicitValues() {
        final Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ModbusChannelConfig.OBJECT_TYPE, "COIL");
        settings.put(ModbusChannelConfig.ADDRESS, "42");
        settings.put(ModbusChannelConfig.REGISTER_COUNT, "3");
        settings.put(ModbusChannelConfig.DATA_ORDER, "4321");

        final ModbusChannelConfig config = ModbusChannelConfig.from(settings, DataType.BYTE_ARRAY);

        assertThat(config.getObjectType()).isEqualTo(ModbusChannelConfig.ObjectType.COIL);
        assertThat(config.getAddress()).isEqualTo(42);
        assertThat(config.getRegisterCount()).isEqualTo(3);
        assertThat(config.getDataOrder()).isEqualTo(ModbusDataOrder.LITTLE_ENDIAN);
    }

    @Test
    public void deriveRegisterCountFromValueType() {
        assertThat(ModbusChannelConfig.from(Map.of(), DataType.INTEGER).getRegisterCount()).isEqualTo(1);
        assertThat(ModbusChannelConfig.from(Map.of(), DataType.LONG).getRegisterCount()).isEqualTo(2);
        assertThat(ModbusChannelConfig.from(Map.of(), DataType.FLOAT).getRegisterCount()).isEqualTo(2);
        assertThat(ModbusChannelConfig.from(Map.of(), DataType.DOUBLE).getRegisterCount()).isEqualTo(4);
        assertThat(ModbusChannelConfig.from(Map.of(), DataType.STRING).getRegisterCount()).isEqualTo(1);
    }

    @Test
    public void acceptBooleanCoilAndByteArrayBits() {
        assertThat(ModbusChannelConfig.from(Map.of(ModbusChannelConfig.OBJECT_TYPE, "COIL"), DataType.BOOLEAN)
                .getRegisterCount()).isEqualTo(1);
        assertThat(ModbusChannelConfig.from(Map.of(ModbusChannelConfig.OBJECT_TYPE, "DISCRETE_INPUT",
                ModbusChannelConfig.REGISTER_COUNT, 8), DataType.BYTE_ARRAY).getRegisterCount()).isEqualTo(8);
    }

    @Test
    public void rejectBooleanHoldingRegister() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(), DataType.BOOLEAN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("COIL");
    }

    @Test
    public void rejectBooleanCoilWithMoreThanOneBit() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.OBJECT_TYPE, "COIL",
                ModbusChannelConfig.REGISTER_COUNT, 2), DataType.BOOLEAN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one Modbus bit");
    }

    @Test
    public void rejectNumericBitChannel() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.OBJECT_TYPE, "COIL"),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOOLEAN");
    }

    @Test
    public void rejectWrongRegisterCountForFloatAndDouble() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.REGISTER_COUNT, 1),
                DataType.FLOAT)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FLOAT");
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.REGISTER_COUNT, 2),
                DataType.DOUBLE)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DOUBLE");
    }

    @Test
    public void rejectWrongRegisterCountForIntegerAndLong() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.REGISTER_COUNT, 4),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INTEGER");
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.REGISTER_COUNT, 3),
                DataType.LONG)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LONG");
    }

    @Test
    public void rejectOutOfRangeAddressAndCount() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.ADDRESS, 70000),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("address");
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.REGISTER_COUNT, 0),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.REGISTER_COUNT, 126),
                DataType.STRING)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("125");
    }

    @Test
    public void rejectUnknownObjectTypeAndOrder() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.OBJECT_TYPE, "NOPE"),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOPE");
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.DATA_ORDER, "NOPE"),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOPE");
    }

    @Test
    public void rejectNonNumericValues() {
        assertThatThrownBy(() -> ModbusChannelConfig.from(Map.of(ModbusChannelConfig.ADDRESS, "x"),
                DataType.INTEGER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numeric");
    }
}
