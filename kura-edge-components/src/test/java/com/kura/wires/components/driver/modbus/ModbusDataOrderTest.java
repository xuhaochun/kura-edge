/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 字节序：四种线序的已知字面量，以及 toWire/toBigEndian 的互逆性。 */
class ModbusDataOrderTest {

    private static final byte[] BIG_ENDIAN = {0x01, 0x02, 0x03, 0x04};

    @Test
    public void mapFourByteOrdersToKnownWireLayouts() {
        assertThat(ModbusDataOrder.BIG_ENDIAN.toWire(BIG_ENDIAN)).containsExactly(0x01, 0x02, 0x03, 0x04);
        assertThat(ModbusDataOrder.LITTLE_ENDIAN.toWire(BIG_ENDIAN)).containsExactly(0x04, 0x03, 0x02, 0x01);
        assertThat(ModbusDataOrder.BIG_ENDIAN_WORD_SWAP.toWire(BIG_ENDIAN)).containsExactly(0x03, 0x04, 0x01, 0x02);
        assertThat(ModbusDataOrder.LITTLE_ENDIAN_WORD_SWAP.toWire(BIG_ENDIAN))
                .containsExactly(0x02, 0x01, 0x04, 0x03);
    }

    @Test
    public void mapTwoByteOrders() {
        final byte[] value = {0x12, 0x34};

        assertThat(ModbusDataOrder.BIG_ENDIAN.toWire(value)).containsExactly(0x12, 0x34);
        assertThat(ModbusDataOrder.LITTLE_ENDIAN.toWire(value)).containsExactly(0x34, 0x12);
        assertThat(ModbusDataOrder.BIG_ENDIAN_WORD_SWAP.toWire(value)).containsExactly(0x12, 0x34);
        assertThat(ModbusDataOrder.LITTLE_ENDIAN_WORD_SWAP.toWire(value)).containsExactly(0x34, 0x12);
    }

    @Test
    public void roundTripEveryOrder() {
        for (final ModbusDataOrder order : ModbusDataOrder.values()) {
            assertThat(order.toBigEndian(order.toWire(BIG_ENDIAN))).containsExactly(BIG_ENDIAN);
        }
    }

    @Test
    public void keepNonNumericByteArraysUntouched() {
        final byte[] text = {0x41, 0x42, 0x43};

        assertThat(ModbusDataOrder.LITTLE_ENDIAN.toWire(text)).isSameAs(text);
        assertThat(ModbusDataOrder.LITTLE_ENDIAN.toBigEndian(text)).isSameAs(text);
    }

    @Test
    public void parseNamesAndUpstreamDigitStrings() {
        assertThat(ModbusDataOrder.fromConfig(null)).isEqualTo(ModbusDataOrder.BIG_ENDIAN);
        assertThat(ModbusDataOrder.fromConfig("BIG_ENDIAN")).isEqualTo(ModbusDataOrder.BIG_ENDIAN);
        assertThat(ModbusDataOrder.fromConfig("1234")).isEqualTo(ModbusDataOrder.BIG_ENDIAN);
        assertThat(ModbusDataOrder.fromConfig("12")).isEqualTo(ModbusDataOrder.BIG_ENDIAN);
        assertThat(ModbusDataOrder.fromConfig("4321")).isEqualTo(ModbusDataOrder.LITTLE_ENDIAN);
        assertThat(ModbusDataOrder.fromConfig("21")).isEqualTo(ModbusDataOrder.LITTLE_ENDIAN);
        assertThat(ModbusDataOrder.fromConfig("3412")).isEqualTo(ModbusDataOrder.BIG_ENDIAN_WORD_SWAP);
        assertThat(ModbusDataOrder.fromConfig("2143")).isEqualTo(ModbusDataOrder.LITTLE_ENDIAN_WORD_SWAP);
    }

    @Test
    public void rejectUnknownOrder() {
        assertThatThrownBy(() -> ModbusDataOrder.fromConfig("X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X");
    }
}
