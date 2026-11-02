/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;

/** 通道取值编解码：每个 {@link DataType} 的已知字面量与字节序组合。 */
class ModbusValueCodecTest {

    @Test
    public void decodeOneRegisterAsInteger() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x1234}, DataType.INTEGER, 1,
                ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newIntegerValue(0x1234));
    }

    @Test
    public void decodeTwoRegistersAsInteger() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x1234, 0x5678}, DataType.INTEGER, 2,
                ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newIntegerValue(0x12345678));
    }

    @Test
    public void decodeLittleEndianInteger() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x3412}, DataType.INTEGER, 1,
                ModbusDataOrder.LITTLE_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newIntegerValue(0x1234));
    }

    @Test
    public void decodeLongFromTwoRegistersAsUnsigned32() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x89AB, 0xCDEF}, DataType.LONG, 2,
                ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newLongValue(0x89ABCDEFL));
    }

    @Test
    public void decodeLongFromFourRegisters() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(
                new int[] {0x0123, 0x4567, 0x89AB, 0xCDEF}, DataType.LONG, 4, ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newLongValue(0x0123456789ABCDEFL));
    }

    @Test
    public void decodeFloat() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x3FC0, 0x0000}, DataType.FLOAT, 2,
                ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newFloatValue(1.5f));
    }

    @Test
    public void decodeDouble() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x3FF8, 0x0000, 0x0000, 0x0000},
                DataType.DOUBLE, 4, ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newDoubleValue(1.5d));
    }

    @Test
    public void decodeStringAndStripTrailingZeros() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x4142, 0x0000}, DataType.STRING, 2,
                ModbusDataOrder.BIG_ENDIAN);

        assertThat(value).isEqualTo(TypedValues.newStringValue("AB"));
    }

    @Test
    public void decodeByteArray() {
        final TypedValue<?> value = ModbusValueCodec.decodeRegisters(new int[] {0x0102}, DataType.BYTE_ARRAY, 1,
                ModbusDataOrder.BIG_ENDIAN);

        assertThat((byte[]) value.getValue()).containsExactly(0x01, 0x02);
    }

    @Test
    public void encodeIntegerBackToRegisters() {
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newIntegerValue(0x1234), DataType.INTEGER, 1,
                ModbusDataOrder.BIG_ENDIAN)).containsExactly(0x1234);
    }

    @Test
    public void encodeLittleEndianInteger() {
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newIntegerValue(0x1234), DataType.INTEGER, 1,
                ModbusDataOrder.LITTLE_ENDIAN)).containsExactly(0x3412);
    }

    @Test
    public void encodeLongFromTwoRegisters() {
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newLongValue(0x89ABCDEFL), DataType.LONG, 2,
                ModbusDataOrder.BIG_ENDIAN)).containsExactly(0x89AB, 0xCDEF);
    }

    @Test
    public void encodeFloatAndDouble() {
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newFloatValue(1.5f), DataType.FLOAT, 2,
                ModbusDataOrder.BIG_ENDIAN)).containsExactly(0x3FC0, 0x0000);
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newDoubleValue(1.5d), DataType.DOUBLE, 4,
                ModbusDataOrder.BIG_ENDIAN)).containsExactly(0x3FF8, 0, 0, 0);
    }

    @Test
    public void encodeStringAndByteArrayWithZeroPadding() {
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newStringValue("AB"), DataType.STRING, 2,
                ModbusDataOrder.BIG_ENDIAN)).containsExactly(0x4142, 0x0000);
        assertThat(ModbusValueCodec.encodeRegisters(TypedValues.newByteArrayValue(new byte[] {0x01, 0x02}),
                DataType.BYTE_ARRAY, 1, ModbusDataOrder.BIG_ENDIAN)).containsExactly(0x0102);
    }

    @Test
    public void decodeAndEncodeBooleanBits() {
        final TypedValue<?> value = ModbusValueCodec.decodeBits(new boolean[] {true}, DataType.BOOLEAN, 1);

        assertThat(value).isEqualTo(TypedValues.newBooleanValue(true));
        assertThat(ModbusValueCodec.encodeBits(TypedValues.newBooleanValue(false), DataType.BOOLEAN, 1))
                .containsExactly(false);
    }

    @Test
    public void packAndUnpackByteArrayBits() {
        final TypedValue<?> packed = ModbusValueCodec.decodeBits(new boolean[] {true, false, true}, DataType.BYTE_ARRAY,
                3);

        assertThat((byte[]) packed.getValue()).containsExactly((byte) 0x05);
        assertThat(ModbusValueCodec.encodeBits(TypedValues.newByteArrayValue(new byte[] {0x05}), DataType.BYTE_ARRAY,
                3)).containsExactly(true, false, true);
    }

    @Test
    public void roundTripFourByteValuesWithEveryOrder() {
        for (final ModbusDataOrder order : ModbusDataOrder.values()) {
            final int[] registers = ModbusValueCodec.encodeRegisters(TypedValues.newIntegerValue(0x01020304),
                    DataType.INTEGER, 2, order);
            final TypedValue<?> decoded = ModbusValueCodec.decodeRegisters(registers, DataType.INTEGER, 2, order);

            assertThat(decoded).isEqualTo(TypedValues.newIntegerValue(0x01020304));
        }
    }

    @Test
    public void rejectRegisterCountMismatch() {
        assertThatThrownBy(() -> ModbusValueCodec.decodeRegisters(new int[] {1}, DataType.INTEGER, 2,
                ModbusDataOrder.BIG_ENDIAN)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2");
    }

    @Test
    public void rejectBooleanRegisterValue() {
        assertThatThrownBy(() -> ModbusValueCodec.decodeRegisters(new int[] {1}, DataType.BOOLEAN, 1,
                ModbusDataOrder.BIG_ENDIAN)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    public void rejectWrongJavaTypeForEncode() {
        assertThatThrownBy(() -> ModbusValueCodec.encodeRegisters(TypedValues.newStringValue("x"), DataType.INTEGER, 1,
                ModbusDataOrder.BIG_ENDIAN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void rejectBitValueForRegisterObject() {
        assertThatThrownBy(() -> ModbusValueCodec.decodeBits(new boolean[] {true}, DataType.INTEGER, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
