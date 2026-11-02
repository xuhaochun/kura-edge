/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.AdOption;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.driver.ChannelDescriptor;

/**
 * Modbus 驱动的通道描述符：对象类型、起始地址、寄存器/位数量和字节序。
 * <p>
 * 参数 id <b>不带</b> {@code +} 前缀（{@code +} 段是资产基座参数的判据），因此快照里的键形如
 * {@code temperature#modbus.address}。取值类型不在这里重复声明：资产通道的 {@code +value.type}
 * 已决定驱动应读出的 Java 类型，避免同一通道存在两份互相矛盾的“类型”配置。
 * </p>
 */
public final class ModbusChannelDescriptor implements ChannelDescriptor {

    private static final List<Ad> DESCRIPTOR = build();

    @Override
    public Object getDescriptor() {
        return DESCRIPTOR;
    }

    /**
     * 返回通道参数定义（顺序固定：对象类型、地址、数量、字节序）。
     *
     * @return AD 列表
     */
    public static List<Ad> ads() {
        return DESCRIPTOR;
    }

    private static List<Ad> build() {
        final List<Ad> ads = new ArrayList<>();

        final Ad objectType = new Ad(ModbusChannelConfig.OBJECT_TYPE, "modbus.object.type", Scalar.STRING, true,
                ModbusChannelConfig.ObjectType.HOLDING_REGISTER.name());
        objectType.setDescription("Modbus object to access: COIL, DISCRETE_INPUT, HOLDING_REGISTER or INPUT_REGISTER");
        objectType.setOptions(List.of(
                new AdOption("Coil", ModbusChannelConfig.ObjectType.COIL.name()),
                new AdOption("Discrete input", ModbusChannelConfig.ObjectType.DISCRETE_INPUT.name()),
                new AdOption("Holding register", ModbusChannelConfig.ObjectType.HOLDING_REGISTER.name()),
                new AdOption("Input register", ModbusChannelConfig.ObjectType.INPUT_REGISTER.name())));
        ads.add(objectType);

        final Ad address = new Ad(ModbusChannelConfig.ADDRESS, "modbus.address", Scalar.INTEGER, true, 0);
        address.setDescription("Zero-based Modbus address of the first register/coil");
        address.setMin(0.0d);
        address.setMax(65535.0d);
        ads.add(address);

        final Ad count = new Ad(ModbusChannelConfig.REGISTER_COUNT, "modbus.register.count", Scalar.INTEGER, false, null);
        count.setDescription("Number of 16-bit registers or bits to read/write. When omitted it is derived from the"
                + " channel value type (FLOAT=2, DOUBLE=4, LONG=2, other=1)");
        count.setMin(1.0d);
        count.setMax(2000.0d);
        ads.add(count);

        final Ad dataOrder = new Ad(ModbusChannelConfig.DATA_ORDER, "modbus.data.order", Scalar.STRING, true,
                ModbusDataOrder.BIG_ENDIAN.name());
        dataOrder.setDescription("Byte/word order for multi-register numeric values: BIG_ENDIAN (1234),"
                + " LITTLE_ENDIAN (4321), BIG_ENDIAN_WORD_SWAP (3412) or LITTLE_ENDIAN_WORD_SWAP (2143)");
        dataOrder.setOptions(List.of(
                new AdOption("Big endian (1234)", ModbusDataOrder.BIG_ENDIAN.name()),
                new AdOption("Little endian (4321)", ModbusDataOrder.LITTLE_ENDIAN.name()),
                new AdOption("Big endian, word swap (3412)", ModbusDataOrder.BIG_ENDIAN_WORD_SWAP.name()),
                new AdOption("Little endian, word swap (2143)", ModbusDataOrder.LITTLE_ENDIAN_WORD_SWAP.name())));
        ads.add(dataOrder);

        return Collections.unmodifiableList(ads);
    }
}
