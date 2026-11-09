/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

/**
 * Modbus 协议异常，对照上游 {@code org.eclipse.kura.protocol.modbus.ModbusProtocolException}。
 * <p>
 * 与上游的差异：不依赖 ResourceBundle，消息直接构造；错误码枚举保留，驱动层据此区分
 * 「连接级失败」与「单条记录失败」。
 * </p>
 */
public class ModbusProtocolException extends Exception {

    private static final long serialVersionUID = -6155136065068974723L;

    private final ModbusProtocolErrorCode code;

    /**
     * 创建带错误码的异常。
     *
     * @param code 错误码
     */
    public ModbusProtocolException(final ModbusProtocolErrorCode code) {
        super(code.name());
        this.code = code;
    }

    /**
     * 创建带错误码与补充消息的异常。
     *
     * @param code 错误码
     * @param message 补充消息
     */
    public ModbusProtocolException(final ModbusProtocolErrorCode code, final String message) {
        super(message == null ? code.name() : code.name() + ": " + message);
        this.code = code;
    }

    /**
     * 创建带错误码、补充消息与原因的异常。
     *
     * @param code 错误码
     * @param message 补充消息
     * @param cause 原因
     */
    public ModbusProtocolException(final ModbusProtocolErrorCode code, final String message, final Throwable cause) {
        super(message == null ? code.name() : code.name() + ": " + message, cause);
        this.code = code;
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public ModbusProtocolErrorCode getCode() {
        return this.code;
    }
}
