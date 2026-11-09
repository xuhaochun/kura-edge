/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

/**
 * 多寄存器数值的字节序/字序，对照上游 {@code org.eclipse.kura.protocol.modbus.ModbusDataOrder}。
 * <p>
 * 上游用数字串表示「wire 上第 1..4 个字节来自大端值的第几个字节」：
 * {@code 1234} = 大端、{@code 4321} = 小端、{@code 3412} = 字交换、{@code 2143} = 字内字节交换。
 * 本项目只允许这四种值（与上游常量同名），并额外接受数字串以兼容原生配置。
 * </p>
 */
public enum ModbusDataOrder {

    /** 大端（1234）：寄存器顺序与寄存器内字节均为大端。 */
    BIG_ENDIAN("1234"),

    /** 小端（4321）：整个值的字节顺序反转。 */
    LITTLE_ENDIAN("4321"),

    /** 字交换（3412）：16 位寄存器顺序反转，寄存器内字节保持大端。 */
    BIG_ENDIAN_WORD_SWAP("3412"),

    /** 字内字节交换（2143）：寄存器顺序不变，每个寄存器内的高/低字节交换。 */
    LITTLE_ENDIAN_WORD_SWAP("2143");

    private final String digits;

    ModbusDataOrder(final String digits) {
        this.digits = digits;
    }

    /**
     * 解析配置值：接受枚举名或上游数字串（{@code 1234}/{@code 4321}/{@code 3412}/{@code 2143}，
     * 以及 16 位的 {@code 12}/{@code 21}）。缺省为大端。
     *
     * @param value 配置值
     * @return 字节序
     * @throws IllegalArgumentException 值不受支持时
     */
    public static ModbusDataOrder fromConfig(final Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return BIG_ENDIAN;
        }
        final String text = String.valueOf(value).trim();
        switch (text) {
            case "12":
            case "1234":
            case "BIG_ENDIAN":
                return BIG_ENDIAN;
            case "21":
            case "4321":
            case "LITTLE_ENDIAN":
                return LITTLE_ENDIAN;
            case "3412":
            case "BIG_ENDIAN_WORD_SWAP":
                return BIG_ENDIAN_WORD_SWAP;
            case "2143":
            case "LITTLE_ENDIAN_WORD_SWAP":
                return LITTLE_ENDIAN_WORD_SWAP;
            default:
                throw new IllegalArgumentException("Unsupported Modbus data order: " + text);
        }
    }

    /**
     * 把 wire 上的字节（寄存器按大端展开）重排成大端值字节。
     * <p>
     * 只对 2/4/8 字节的数值有意义；其它长度（字符串/字节数组）原样返回，避免把
     * 厂商自定义的文本字节顺序打乱。
     * </p>
     *
     * @param wireBytes wire 字节
     * @return 大端值字节
     */
    public byte[] toBigEndian(final byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length != 2 && wireBytes.length != 4 && wireBytes.length != 8) {
            return wireBytes;
        }
        if (wireBytes.length == 2) {
            return isLittleEndianBytes() ? new byte[] {wireBytes[1], wireBytes[0]}
                    : new byte[] {wireBytes[0], wireBytes[1]};
        }
        final int[] target = targetIndexes(wireBytes.length);
        final byte[] result = new byte[wireBytes.length];
        for (int i = 0; i < wireBytes.length; i++) {
            result[target[i]] = wireBytes[i];
        }
        return result;
    }

    /**
     * 把大端值字节重排成 wire 字节（{@link #toBigEndian(byte[])} 的逆操作）。
     *
     * @param bigEndianBytes 大端值字节
     * @return wire 字节
     */
    public byte[] toWire(final byte[] bigEndianBytes) {
        if (bigEndianBytes == null || bigEndianBytes.length != 2 && bigEndianBytes.length != 4
                && bigEndianBytes.length != 8) {
            return bigEndianBytes;
        }
        if (bigEndianBytes.length == 2) {
            return isLittleEndianBytes() ? new byte[] {bigEndianBytes[1], bigEndianBytes[0]}
                    : new byte[] {bigEndianBytes[0], bigEndianBytes[1]};
        }
        final int[] target = targetIndexes(bigEndianBytes.length);
        final byte[] result = new byte[bigEndianBytes.length];
        for (int i = 0; i < bigEndianBytes.length; i++) {
            result[i] = bigEndianBytes[target[i]];
        }
        return result;
    }

    private boolean isLittleEndianBytes() {
        return this == LITTLE_ENDIAN || this == LITTLE_ENDIAN_WORD_SWAP;
    }

    /**
     * wire 下标 → 大端值字节下标（每 4 字节一组）。
     */
    private int[] targetIndexes(final int length) {
        final int[] target = new int[length];
        for (int i = 0; i < length; i++) {
            final int digit = this.digits.charAt(i % 4) - '1';
            target[i] = i / 4 * 4 + digit;
        }
        return target;
    }
}
