/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;

/**
 * Modbus 寄存器/位与项目 {@link TypedValue} 之间的编解码。
 * <p>
 * 寄存器按大端 16 位进入/离开 wire；多寄存器数值先按 {@link ModbusDataOrder} 重排成大端字节，
 * 再按 Java 标量解码。字符串/字节数组不打乱字节顺序（厂商自定义文本布局），按配置的寄存器数
 * 左对齐、右侧补 0。
 * </p>
 */
public final class ModbusValueCodec {

    private ModbusValueCodec() {
        // 工具类
    }

    /**
     * 把寄存器值解码成通道取值。
     *
     * @param registers 寄存器值（每个 16 位）
     * @param type 目标取值类型
     * @param count 期望的寄存器数
     * @param order 字节序
     * @return 类型化取值
     */
    public static TypedValue<?> decodeRegisters(final int[] registers, final DataType type, final int count,
            final ModbusDataOrder order) {
        if (registers == null || registers.length != count) {
            throw new IllegalArgumentException("Expected " + count + " Modbus register(s), got "
                    + (registers == null ? 0 : registers.length));
        }
        final byte[] wire = registersToWire(registers);
        if (type == DataType.STRING) {
            return TypedValues.newStringValue(stripTrailingZeros(new String(wire, StandardCharsets.UTF_8)));
        }
        if (type == DataType.BYTE_ARRAY) {
            return TypedValues.newByteArrayValue(wire);
        }
        final byte[] bigEndian = order.toBigEndian(wire);
        final ByteBuffer buffer = ByteBuffer.wrap(bigEndian);
        switch (type) {
            case INTEGER:
                return count == 2 ? TypedValues.newIntegerValue(buffer.getInt())
                        : TypedValues.newIntegerValue(buffer.getShort() & 0xffff);
            case LONG:
                return count == 4 ? TypedValues.newLongValue(buffer.getLong())
                        : TypedValues.newLongValue(Integer.toUnsignedLong(buffer.getInt()));
            case FLOAT:
                return TypedValues.newFloatValue(buffer.getFloat());
            case DOUBLE:
                return TypedValues.newDoubleValue(buffer.getDouble());
            default:
                throw new IllegalArgumentException("Unsupported Modbus register value type: " + type);
        }
    }

    /**
     * 把通道取值编码成寄存器值。
     *
     * @param value 类型化取值
     * @param type 取值类型
     * @param count 目标寄存器数
     * @param order 字节序
     * @return 寄存器值
     */
    public static int[] encodeRegisters(final TypedValue<?> value, final DataType type, final int count,
            final ModbusDataOrder order) {
        if (value == null) {
            throw new IllegalArgumentException("Modbus write value cannot be null");
        }
        final int expectedBytes = count * 2;
        byte[] bigEndian;
        switch (type) {
            case INTEGER:
                bigEndian = encodeInteger(((Number) requireValue(value, Number.class)).intValue(), count);
                break;
            case LONG:
                bigEndian = encodeLong(((Number) requireValue(value, Number.class)).longValue(), count);
                break;
            case FLOAT:
                bigEndian = ByteBuffer.allocate(4).putFloat(((Number) requireValue(value, Number.class)).floatValue())
                        .array();
                break;
            case DOUBLE:
                bigEndian = ByteBuffer.allocate(8).putDouble(((Number) requireValue(value, Number.class)).doubleValue())
                        .array();
                break;
            case STRING:
                bigEndian = padTo(String.valueOf(value.getValue()).getBytes(StandardCharsets.UTF_8), expectedBytes);
                break;
            case BYTE_ARRAY:
                bigEndian = padTo((byte[]) requireValue(value, byte[].class), expectedBytes);
                break;
            default:
                throw new IllegalArgumentException("Unsupported Modbus register value type: " + type);
        }
        if (bigEndian.length != expectedBytes) {
            throw new IllegalArgumentException("A " + type + " value requires " + expectedBytes
                    + " bytes, got " + bigEndian.length);
        }
        if (type == DataType.STRING || type == DataType.BYTE_ARRAY) {
            return wireToRegisters(bigEndian);
        }
        return wireToRegisters(order.toWire(bigEndian));
    }

    /**
     * 把线圈/离散输入位解码成通道取值（BOOLEAN 或按 LSB-first 打包的 BYTE_ARRAY）。
     *
     * @param bits 位值
     * @param type 目标类型
     * @param count 期望位数
     * @return 类型化取值
     */
    public static TypedValue<?> decodeBits(final boolean[] bits, final DataType type, final int count) {
        if (bits == null || bits.length != count) {
            throw new IllegalArgumentException("Expected " + count + " Modbus bit(s), got "
                    + (bits == null ? 0 : bits.length));
        }
        if (type == DataType.BOOLEAN) {
            return TypedValues.newBooleanValue(count > 0 && bits[0]);
        }
        if (type == DataType.BYTE_ARRAY) {
            final byte[] packed = new byte[(count + 7) / 8];
            for (int i = 0; i < count; i++) {
                if (bits[i]) {
                    packed[i / 8] = (byte) (packed[i / 8] | 1 << (i % 8));
                }
            }
            return TypedValues.newByteArrayValue(packed);
        }
        throw new IllegalArgumentException("Modbus bit objects require a BOOLEAN or BYTE_ARRAY channel, got " + type);
    }

    /**
     * 把通道取值编码成线圈/离散输入位。
     *
     * @param value 类型化取值
     * @param type 取值类型
     * @param count 目标位数
     * @return 位值
     */
    public static boolean[] encodeBits(final TypedValue<?> value, final DataType type, final int count) {
        if (type == DataType.BOOLEAN) {
            return new boolean[] {Boolean.TRUE.equals(requireValue(value, Boolean.class))};
        }
        if (type == DataType.BYTE_ARRAY) {
            final byte[] bytes = (byte[]) requireValue(value, byte[].class);
            if (bytes.length < (count + 7) / 8) {
                throw new IllegalArgumentException("A BYTE_ARRAY value requires at least " + (count + 7) / 8
                        + " byte(s) for " + count + " Modbus bit(s), got " + bytes.length);
            }
            final boolean[] bits = new boolean[count];
            for (int i = 0; i < count; i++) {
                bits[i] = (bytes[i / 8] & 1 << (i % 8)) != 0;
            }
            return bits;
        }
        throw new IllegalArgumentException("Modbus bit objects require a BOOLEAN or BYTE_ARRAY channel, got " + type);
    }

    private static byte[] encodeInteger(final int value, final int count) {
        if (count == 1) {
            return new byte[] {(byte) (value >> 8), (byte) value};
        }
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] encodeLong(final long value, final int count) {
        if (count == 2) {
            return ByteBuffer.allocate(4).putInt((int) value).array();
        }
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    private static <T> T requireValue(final TypedValue<?> value, final Class<T> expected) {
        if (value == null || !expected.isInstance(value.getValue())) {
            throw new IllegalArgumentException("Expected a " + expected.getSimpleName() + " channel value, got "
                    + (value == null || value.getValue() == null ? "null" : value.getValue().getClass().getSimpleName()));
        }
        return expected.cast(value.getValue());
    }

    private static byte[] padTo(final byte[] bytes, final int length) {
        return Arrays.copyOf(bytes, length);
    }

    private static String stripTrailingZeros(final String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\0') {
            end--;
        }
        return text.substring(0, end);
    }

    private static byte[] registersToWire(final int[] registers) {
        final byte[] wire = new byte[registers.length * 2];
        for (int i = 0; i < registers.length; i++) {
            wire[i * 2] = (byte) (registers[i] >> 8);
            wire[i * 2 + 1] = (byte) registers[i];
        }
        return wire;
    }

    private static int[] wireToRegisters(final byte[] wire) {
        final int[] registers = new int[wire.length / 2];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = (wire[i * 2] & 0xff) << 8 | wire[i * 2 + 1] & 0xff;
        }
        return registers;
    }
}
