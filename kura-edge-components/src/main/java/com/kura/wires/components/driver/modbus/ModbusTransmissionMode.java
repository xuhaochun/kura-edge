/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

/**
 * Modbus 连接类型（本项目只支持 TCP 物理链路），对照上游
 * {@code org.eclipse.kura.protocol.modbus.ModbusProtocolDevice} 的
 * {@code PROTOCOL_CONNECTION_TYPE_*}} 常量。
 * <p>
 * 串口模式需要 OSGi {@code CommConnection}（或新增本地串口库），不在本轮范围内；
 * {@link #TCP} 使用标准 Modbus TCP（MBAP 头），{@link #TCP_RTU} 把 RTU 帧（带 CRC）直接跑在
 * TCP 流上，对照上游 {@code TCP-RTU}。
 * </p>
 */
public enum ModbusTransmissionMode {

    /** 标准 Modbus TCP（MBAP 头，无 CRC）。 */
    TCP,

    /** RTU over TCP（带 CRC-16）。 */
    TCP_RTU;

    /**
     * 解析配置值（兼容上游字符串 {@code TCP/IP} / {@code TCP-RTU}）。
     *
     * @param value 配置值
     * @return 连接类型
     * @throws IllegalArgumentException 值不受支持时
     */
    public static ModbusTransmissionMode fromConfig(final Object value) {
        final String text = value == null ? "" : String.valueOf(value).trim();
        switch (text) {
            case "TCP/IP":
            case "TCP":
                return TCP;
            case "TCP-RTU":
            case "TCP_RTU":
                return TCP_RTU;
            default:
                throw new IllegalArgumentException("Unsupported Modbus connection type: " + text);
        }
    }
}
