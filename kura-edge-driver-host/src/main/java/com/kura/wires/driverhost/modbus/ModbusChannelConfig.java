/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

import java.util.Map;

import com.kura.wires.model.type.DataType;

/**
 * 单个 Modbus 通道的驱动私有配置（从资产通道配置表解析）。
 * <p>
 * 键名不带通道名前缀（资产层已去掉），与项目其它驱动一致：
 * {@code modbus.object.type}、{@code modbus.address}、{@code modbus.register.count}、
 * {@code modbus.data.order}。解析失败抛 {@link IllegalArgumentException}，由驱动标成该条记录
 * 的 FAILURE，不影响同批其它记录。
 * </p>
 */
public final class ModbusChannelConfig {

    /** 对象类型：线圈/离散输入/保持寄存器/输入寄存器。 */
    public static final String OBJECT_TYPE = "modbus.object.type";

    /** 起始地址。 */
    public static final String ADDRESS = "modbus.address";

    /** 读写数量（寄存器数或位对象数）；缺省按取值类型推导。 */
    public static final String REGISTER_COUNT = "modbus.register.count";

    /** 多寄存器数值的字节序/字序。 */
    public static final String DATA_ORDER = "modbus.data.order";

    /** Modbus 线圈/离散输入一次最多 2000 个。 */
    private static final int MAX_BITS = 2000;

    /** Modbus 寄存器一次最多 125 个。 */
    private static final int MAX_REGISTERS = 125;

    /** Modbus 地址上限。 */
    private static final int MAX_ADDRESS = 65535;

    /**
     * Modbus 对象类型。
     */
    public enum ObjectType {

        /** 线圈：可读可写，功能码 01/05/15。 */
        COIL(true, true),

        /** 离散输入：只读，功能码 02。 */
        DISCRETE_INPUT(true, false),

        /** 保持寄存器：可读可写，功能码 03/06/16。 */
        HOLDING_REGISTER(false, true),

        /** 输入寄存器：只读，功能码 04。 */
        INPUT_REGISTER(false, false);

        private final boolean bit;
        private final boolean writable;

        ObjectType(final boolean bit, final boolean writable) {
            this.bit = bit;
            this.writable = writable;
        }

        /**
         * 是否位对象（线圈/离散输入）。
         *
         * @return 位对象时为 true
         */
        public boolean isBit() {
            return this.bit;
        }

        /**
         * 是否可写。
         *
         * @return 可写时为 true
         */
        public boolean isWritable() {
            return this.writable;
        }

        /**
         * 解析配置值（枚举名）。
         *
         * @param value 配置值
         * @return 对象类型
         */
        public static ObjectType fromConfig(final Object value) {
            if (value == null || String.valueOf(value).isBlank()) {
                return HOLDING_REGISTER;
            }
            final String text = String.valueOf(value).trim();
            try {
                return ObjectType.valueOf(text);
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("Unsupported Modbus object type: " + text, e);
            }
        }
    }

    private final ObjectType objectType;

    private final int address;

    private final int registerCount;

    private final ModbusDataOrder dataOrder;

    private ModbusChannelConfig(final ObjectType objectType, final int address, final int registerCount,
            final ModbusDataOrder dataOrder) {
        this.objectType = objectType;
        this.address = address;
        this.registerCount = registerCount;
        this.dataOrder = dataOrder;
    }

    /**
     * 解析通道配置并校验与取值类型的兼容性。
     *
     * @param config 通道私有配置（键为 {@code modbus.*}）
     * @param valueType 资产通道的取值类型（驱动应读出的原始类型）
     * @return 解析结果
     * @throws IllegalArgumentException 配置非法时
     */
    public static ModbusChannelConfig from(final Map<String, Object> config, final DataType valueType) {
        if (valueType == null) {
            throw new IllegalArgumentException("Channel value type cannot be null");
        }
        final Map<String, Object> settings = config == null ? Map.of() : config;
        final ObjectType objectType = ObjectType.fromConfig(settings.get(OBJECT_TYPE));
        final int address = intValue(settings.get(ADDRESS), 0);
        if (address < 0 || address > MAX_ADDRESS) {
            throw new IllegalArgumentException("Invalid Modbus address: " + address);
        }
        final ModbusDataOrder order = ModbusDataOrder.fromConfig(settings.get(DATA_ORDER));
        final Integer configuredCount = optionalInt(settings.get(REGISTER_COUNT));
        final int count = configuredCount == null ? defaultCount(valueType) : configuredCount;
        validateCount(objectType, valueType, count);
        return new ModbusChannelConfig(objectType, address, count, order);
    }

    private static int defaultCount(final DataType valueType) {
        switch (valueType) {
            case DOUBLE:
                return 4;
            case FLOAT:
            case LONG:
                return 2;
            default:
                return 1;
        }
    }

    private static void validateCount(final ObjectType objectType, final DataType valueType, final int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Modbus register/bit count must be positive: " + count);
        }
        if (objectType.isBit()) {
            if (count > MAX_BITS) {
                throw new IllegalArgumentException("Modbus bit count exceeds " + MAX_BITS + ": " + count);
            }
            if (valueType == DataType.BOOLEAN && count != 1) {
                throw new IllegalArgumentException("A BOOLEAN channel must use exactly one Modbus bit");
            }
            if (valueType != DataType.BOOLEAN && valueType != DataType.BYTE_ARRAY) {
                throw new IllegalArgumentException("Modbus bit objects require a BOOLEAN or BYTE_ARRAY channel, got "
                        + valueType);
            }
            return;
        }
        if (count > MAX_REGISTERS) {
            throw new IllegalArgumentException("Modbus register count exceeds " + MAX_REGISTERS + ": " + count);
        }
        switch (valueType) {
            case BOOLEAN:
                throw new IllegalArgumentException("A BOOLEAN channel requires a COIL or DISCRETE_INPUT object");
            case FLOAT:
                requireCount(valueType, count, 2);
                break;
            case DOUBLE:
                requireCount(valueType, count, 4);
                break;
            case LONG:
                requireCount(valueType, count, 2, 4);
                break;
            case INTEGER:
                requireCount(valueType, count, 1, 2);
                break;
            default:
                break;
        }
    }

    private static void requireCount(final DataType valueType, final int count, final int... allowed) {
        for (final int candidate : allowed) {
            if (count == candidate) {
                return;
            }
        }
        final StringBuilder expected = new StringBuilder();
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) {
                expected.append(" or ");
            }
            expected.append(allowed[i]);
        }
        throw new IllegalArgumentException(
                "A " + valueType + " channel requires " + expected + " Modbus register(s), got " + count);
    }

    private static int intValue(final Object value, final int defaultValue) {
        final Integer parsed = optionalInt(value);
        return parsed == null ? defaultValue : parsed;
    }

    private static Integer optionalInt(final Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Modbus numeric value: " + value, e);
        }
    }

    public ObjectType getObjectType() {
        return this.objectType;
    }

    public int getAddress() {
        return this.address;
    }

    public int getRegisterCount() {
        return this.registerCount;
    }

    public ModbusDataOrder getDataOrder() {
        return this.dataOrder;
    }
}
