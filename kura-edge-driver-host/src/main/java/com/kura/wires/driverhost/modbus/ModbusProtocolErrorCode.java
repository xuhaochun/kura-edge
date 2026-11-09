/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

/**
 * Modbus 协议错误码，对照上游 {@code org.eclipse.kura.protocol.modbus.ModbusProtocolErrorCode}。
 */
public enum ModbusProtocolErrorCode {

    /** 配置缺失或非法。 */
    INVALID_CONFIGURATION,

    /** 地址/数量越界。 */
    INVALID_DATA_ADDRESS,

    /** 响应长度或内容非法。 */
    INVALID_DATA_TYPE,

    /** 方法不支持（如 ASCII over TCP、写只读对象）。 */
    METHOD_NOT_SUPPORTED,

    /** 连接尚未建立。 */
    NOT_CONNECTED,

    /** 连接失败。 */
    CONNECTION_FAILURE,

    /** 事务失败（响应校验/IO）。 */
    TRANSACTION_FAILURE,

    /** 响应超时。 */
    RESPONSE_TIMEOUT
}
