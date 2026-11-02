/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

/**
 * 支持的 Modbus 功能码，对照上游 {@code org.eclipse.kura.protocol.modbus.ModbusFunctionCodes}。
 */
public final class ModbusFunctionCodes {

    /** 读线圈（01）。 */
    public static final int READ_COIL_STATUS = 1;

    /** 读离散输入（02）。 */
    public static final int READ_INPUT_STATUS = 2;

    /** 读保持寄存器（03）。 */
    public static final int READ_HOLDING_REGS = 3;

    /** 读输入寄存器（04）。 */
    public static final int READ_INPUT_REGS = 4;

    /** 写单个线圈（05）。 */
    public static final int FORCE_SINGLE_COIL = 5;

    /** 写单个寄存器（06）。 */
    public static final int PRESET_SINGLE_REG = 6;

    /** 写多个线圈（15）。 */
    public static final int FORCE_MULTIPLE_COILS = 15;

    /** 写多个寄存器（16）。 */
    public static final int PRESET_MULTIPLE_REGS = 16;

    private ModbusFunctionCodes() {
        // 常量类
    }
}
