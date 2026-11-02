/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改：去掉 OSGi/Kura CommConnection，只保留 TCP 与 RTU-over-TCP、
 * 功能码 01/02/03/04/05/06/15/16，以及事务/响应/CRC 校验。
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modbus 主站协议实现（TCP/IP 与 RTU-over-TCP）。
 * <p>
 * 对照上游 {@code org.eclipse.kura.protocol.modbus.ModbusProtocolDevice} 的核心事务流程：
 * 组帧 → 发送 → 读响应 → 校验事务号/unit id/功能码/CRC → 返回 PDU。与上游的差异：
 * </p>
 * <ul>
 * <li>去掉串口（{@code CommConnection}）与 ASCII 模式，只保留 TCP 物理链路；</li>
 * <li>去掉了异常状态/通信事件等不影响资产读写的功能码；</li>
 * <li>错误直接抛 {@link ModbusProtocolException}（带错误码），由驱动层决定整批失败还是单条失败。</li>
 * </ul>
 */
public final class ModbusProtocolDevice {

    private static final Logger log = LoggerFactory.getLogger(ModbusProtocolDevice.class);

    /** TCP MBAP 头长度。 */
    private static final int MBAP_HEADER_LENGTH = 6;

    /** RTU 最小帧长度（unit + func + 2 字节 CRC）。 */
    private static final int RTU_MINIMUM_LENGTH = 5;

    /** 响应帧上限（Modbus 最大 PDU 253 + MBAP/Crc 余量）。 */
    private static final int MAX_RESPONSE_LENGTH = 262;

    /** 写单个对象的 RTU 响应长度。 */
    private static final int RTU_WRITE_ECHO_LENGTH = 8;

    /** 读取/写入的最大位对象数。 */
    private static final int MAX_BITS = 2000;

    /** 读取/写入的最大寄存器数。 */
    private static final int MAX_REGISTERS = 125;

    /** Modbus 地址上限。 */
    private static final int MAX_ADDRESS = 65535;

    private final Object transactionLock = new Object();

    private ModbusTransmissionMode mode = ModbusTransmissionMode.TCP;

    private String ipAddress;

    private int port;

    private int unitId = 1;

    private int responseTimeoutMillis = 1000;

    private int maxRetries = 2;

    private boolean configured;

    private Socket socket;

    private InputStream inputStream;

    private OutputStream outputStream;

    private int transactionIndex;

    /**
     * 按配置建立连接参数（不建立 socket）。
     * <p>
     * 配置键沿用上游传输层命名：{@code connectionType}、{@code ipAddress}、{@code ethport}、
     * {@code respTimeout}、{@code unitId}、{@code maxRetries}。
     * </p>
     *
     * @param config 连接配置
     * @throws ModbusProtocolException 配置缺失/非法时
     */
    public void configureConnection(final Properties config) throws ModbusProtocolException {
        if (config == null) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION, "Missing connection config");
        }
        try {
            this.mode = ModbusTransmissionMode.fromConfig(config.getProperty("connectionType"));
            this.ipAddress = require(config, "ipAddress");
            this.port = parseRange(require(config, "ethport"), 1, 65535, "TCP port");
            this.unitId = parseRange(valueOrDefault(config.getProperty("unitId"), "1"), 0, 255, "unit id");
            this.responseTimeoutMillis = parseRange(valueOrDefault(config.getProperty("respTimeout"), "1000"), 1,
                    60000, "response timeout");
            this.maxRetries = parseRange(valueOrDefault(config.getProperty("maxRetries"), "2"), 0, 10, "retry count");
        } catch (final IllegalArgumentException e) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION, e.getMessage(), e);
        }
        disconnect();
        this.configured = true;
    }

    /**
     * 建立 TCP 连接（已连接时直接返回）。
     *
     * @throws ModbusProtocolException 未配置或连接失败时
     */
    public void connect() throws ModbusProtocolException {
        if (!this.configured) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION, "Modbus is not configured");
        }
        if (this.socket != null && this.socket.isConnected() && !this.socket.isClosed()) {
            return;
        }
        try {
            final Socket candidate = new Socket();
            candidate.connect(new InetSocketAddress(this.ipAddress, this.port), this.responseTimeoutMillis);
            candidate.setSoTimeout(this.responseTimeoutMillis);
            candidate.setTcpNoDelay(true);
            this.socket = candidate;
            this.inputStream = candidate.getInputStream();
            this.outputStream = candidate.getOutputStream();
            log.debug("Modbus connected to {}:{} ({})", this.ipAddress, this.port, this.mode);
        } catch (final IOException e) {
            closeQuietly();
            throw new ModbusProtocolException(ModbusProtocolErrorCode.CONNECTION_FAILURE,
                    "Cannot connect to " + this.ipAddress + ":" + this.port, e);
        }
    }

    /**
     * 断开连接（可重复调用）。
     */
    public void disconnect() {
        closeQuietly();
    }

    /**
     * 是否已连接。
     *
     * @return 已连接时为 true
     */
    public boolean isConnected() {
        return this.socket != null && this.socket.isConnected() && !this.socket.isClosed();
    }

    /**
     * 返回配置的 unit id。
     *
     * @return unit id
     */
    public int getUnitId() {
        return this.unitId;
    }

    /**
     * 功能码 01：读线圈。
     *
     * @param unitAddr unit id
     * @param dataAddress 起始地址
     * @param count 数量（1–2000）
     * @return 位值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public boolean[] readCoils(final int unitAddr, final int dataAddress, final int count)
            throws ModbusProtocolException {
        return readBooleans(unitAddr, dataAddress, count, ModbusFunctionCodes.READ_COIL_STATUS);
    }

    /**
     * 功能码 02：读离散输入。
     *
     * @param unitAddr unit id
     * @param dataAddress 起始地址
     * @param count 数量（1–2000）
     * @return 位值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public boolean[] readDiscreteInputs(final int unitAddr, final int dataAddress, final int count)
            throws ModbusProtocolException {
        return readBooleans(unitAddr, dataAddress, count, ModbusFunctionCodes.READ_INPUT_STATUS);
    }

    /**
     * 功能码 03：读保持寄存器。
     *
     * @param unitAddr unit id
     * @param dataAddress 起始地址
     * @param count 数量（1–125）
     * @return 寄存器值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public int[] readHoldingRegisters(final int unitAddr, final int dataAddress, final int count)
            throws ModbusProtocolException {
        return readRegisters(unitAddr, dataAddress, count, ModbusFunctionCodes.READ_HOLDING_REGS);
    }

    /**
     * 功能码 04：读输入寄存器。
     *
     * @param unitAddr unit id
     * @param dataAddress 起始地址
     * @param count 数量（1–125）
     * @return 寄存器值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public int[] readInputRegisters(final int unitAddr, final int dataAddress, final int count)
            throws ModbusProtocolException {
        return readRegisters(unitAddr, dataAddress, count, ModbusFunctionCodes.READ_INPUT_REGS);
    }

    /**
     * 功能码 05：写单个线圈。
     *
     * @param unitAddr unit id
     * @param dataAddress 地址
     * @param data 值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public void writeSingleCoil(final int unitAddr, final int dataAddress, final boolean data)
            throws ModbusProtocolException {
        final byte[] cmd = new byte[] {(byte) unitAddr, (byte) ModbusFunctionCodes.FORCE_SINGLE_COIL,
                high(dataAddress), low(dataAddress), data ? (byte) 0xff : 0, 0};
        verifyEcho(cmd, transact(cmd));
    }

    /**
     * 功能码 06：写单个寄存器。
     *
     * @param unitAddr unit id
     * @param dataAddress 地址
     * @param data 值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public void writeSingleRegister(final int unitAddr, final int dataAddress, final int data)
            throws ModbusProtocolException {
        final byte[] cmd = new byte[] {(byte) unitAddr, (byte) ModbusFunctionCodes.PRESET_SINGLE_REG,
                high(dataAddress), low(dataAddress), high(data), low(data)};
        verifyEcho(cmd, transact(cmd));
    }

    /**
     * 功能码 15：写多个线圈。
     *
     * @param unitAddr unit id
     * @param dataAddress 起始地址
     * @param data 位值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public void writeMultipleCoils(final int unitAddr, final int dataAddress, final boolean[] data)
            throws ModbusProtocolException {
        validateCount(data.length, MAX_BITS, "coil");
        final int dataLength = (data.length + 7) / 8;
        final byte[] cmd = buildMultipleWriteCommand(unitAddr, ModbusFunctionCodes.FORCE_MULTIPLE_COILS, dataAddress,
                data.length, dataLength);
        for (int index = 0; index < data.length; index++) {
            if (data[index]) {
                cmd[7 + index / 8] = (byte) (cmd[7 + index / 8] | 1 << index % 8);
            }
        }
        verifyEcho(cmd, transact(cmd));
    }

    /**
     * 功能码 16：写多个寄存器。
     *
     * @param unitAddr unit id
     * @param dataAddress 起始地址
     * @param data 寄存器值
     * @throws ModbusProtocolException 协议/连接失败时
     */
    public void writeMultipleRegister(final int unitAddr, final int dataAddress, final int[] data)
            throws ModbusProtocolException {
        validateCount(data.length, MAX_REGISTERS, "register");
        final byte[] cmd = buildMultipleWriteCommand(unitAddr, ModbusFunctionCodes.PRESET_MULTIPLE_REGS, dataAddress,
                data.length, data.length * 2);
        for (int index = 0; index < data.length; index++) {
            cmd[7 + index * 2] = high(data[index]);
            cmd[7 + index * 2 + 1] = low(data[index]);
        }
        verifyEcho(cmd, transact(cmd));
    }

    private boolean[] readBooleans(final int unitAddr, final int dataAddress, final int count, final int functionCode)
            throws ModbusProtocolException {
        validateAddress(dataAddress);
        validateCount(count, MAX_BITS, "coil");
        final byte[] cmd = new byte[] {(byte) unitAddr, (byte) functionCode, high(dataAddress), low(dataAddress),
                high(count), low(count)};
        final byte[] response = transact(cmd);
        checkReadResponse(response, functionCode, (count + 7) / 8);
        return unpackBooleans(response, count);
    }

    private int[] readRegisters(final int unitAddr, final int dataAddress, final int count, final int functionCode)
            throws ModbusProtocolException {
        validateAddress(dataAddress);
        validateCount(count, MAX_REGISTERS, "register");
        final byte[] cmd = new byte[] {(byte) unitAddr, (byte) functionCode, high(dataAddress), low(dataAddress), 0,
                low(count)};
        final byte[] response = transact(cmd);
        checkReadResponse(response, functionCode, count * 2);
        final int[] values = new int[count];
        for (int index = 0; index < count; index++) {
            values[index] = (response[2 + index * 2] & 0xff) << 8 | response[3 + index * 2] & 0xff;
        }
        return values;
    }

    private byte[] transact(final byte[] request) throws ModbusProtocolException {
        synchronized (this.transactionLock) {
            ModbusProtocolException last = null;
            for (int attempt = 0; attempt <= this.maxRetries; attempt++) {
                try {
                    connect();
                    return execute(request);
                } catch (final ModbusProtocolException e) {
                    last = e;
                    disconnect();
                    if (!isRetryable(e) || attempt == this.maxRetries) {
                        throw e;
                    }
                    log.debug("Retrying Modbus transaction after {}: {}", e.getCode(), e.getMessage());
                }
            }
            throw last == null
                    ? new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Modbus transaction failed")
                    : last;
        }
    }

    private byte[] execute(final byte[] request) throws ModbusProtocolException {
        final int transaction = nextTransactionIndex();
        final byte[] frame = buildFrame(request, transaction);
        try {
            drainInput();
            this.outputStream.write(frame);
            this.outputStream.flush();
            return readResponse(transaction, request[0] & 0xff);
        } catch (final SocketTimeoutException e) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.RESPONSE_TIMEOUT, "Modbus response timeout", e);
        } catch (final IOException e) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.CONNECTION_FAILURE, "Modbus I/O failure", e);
        }
    }

    private byte[] buildFrame(final byte[] request, final int transaction) {
        if (this.mode == ModbusTransmissionMode.TCP) {
            final byte[] frame = new byte[MBAP_HEADER_LENGTH + request.length];
            frame[0] = (byte) (transaction >> 8);
            frame[1] = (byte) transaction;
            frame[2] = 0;
            frame[3] = 0;
            final int length = request.length;
            frame[4] = (byte) (length >> 8);
            frame[5] = (byte) length;
            System.arraycopy(request, 0, frame, MBAP_HEADER_LENGTH, request.length);
            return frame;
        }
        final byte[] frame = Arrays.copyOf(request, request.length + 2);
        final int crc = Crc16.getCrc16(request, request.length, 0xffff);
        frame[request.length] = (byte) crc;
        frame[request.length + 1] = (byte) (crc >> 8);
        return frame;
    }

    private byte[] readResponse(final int transaction, final int unit) throws ModbusProtocolException, IOException {
        if (this.mode == ModbusTransmissionMode.TCP) {
            return readTcpResponse(transaction, unit);
        }
        return readRtuResponse(unit);
    }

    private byte[] readTcpResponse(final int transaction, final int unit) throws ModbusProtocolException, IOException {
        final byte[] header = readFully(new byte[7]);
        final int responseTransaction = (header[0] & 0xff) << 8 | header[1] & 0xff;
        final int protocol = (header[2] & 0xff) << 8 | header[3] & 0xff;
        final int length = (header[4] & 0xff) << 8 | header[5] & 0xff;
        final int responseUnit = header[6] & 0xff;
        if (responseTransaction != transaction) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Unexpected Modbus transaction id " + responseTransaction + ", expected " + transaction);
        }
        if (protocol != 0) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Unexpected Modbus protocol id " + protocol);
        }
        if (length < 2 || length > MAX_RESPONSE_LENGTH) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE, "Invalid MBAP length " + length);
        }
        if (responseUnit != unit) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Unexpected Modbus unit id " + responseUnit);
        }
        final byte[] pdu = readFully(new byte[length - 1]);
        checkException(pdu);
        return pdu;
    }

    private byte[] readRtuResponse(final int unit) throws ModbusProtocolException, IOException {
        final byte[] head = readFully(new byte[2]);
        if ((head[0] & 0xff) != unit) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Unexpected Modbus unit id " + (head[0] & 0xff));
        }
        final int function = head[1] & 0xff;
        final int remaining;
        if ((function & 0x80) != 0) {
            remaining = 3;
        } else if (function == ModbusFunctionCodes.READ_COIL_STATUS
                || function == ModbusFunctionCodes.READ_INPUT_STATUS
                || function == ModbusFunctionCodes.READ_HOLDING_REGS
                || function == ModbusFunctionCodes.READ_INPUT_REGS) {
            final byte[] countByte = readFully(new byte[1]);
            remaining = (countByte[0] & 0xff) + 2;
            final byte[] rest = readFully(new byte[remaining]);
            final byte[] frame = concat(head, concat(countByte, rest));
            verifyCrc(frame);
            checkException(new byte[] {head[1], countByte[0]});
            return Arrays.copyOfRange(frame, 1, frame.length - 2);
        } else {
            remaining = RTU_WRITE_ECHO_LENGTH - 2;
        }
        final byte[] frame = concat(head, readFully(new byte[remaining]));
        verifyCrc(frame);
        checkException(new byte[] {head[1], frame[2]});
        return Arrays.copyOfRange(frame, 1, frame.length - 2);
    }

    private byte[] readFully(final byte[] buffer) throws IOException, ModbusProtocolException {
        int offset = 0;
        while (offset < buffer.length) {
            final int read = this.inputStream.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        "End of stream while reading Modbus response");
            }
            offset += read;
        }
        return buffer;
    }

    private void drainInput() throws IOException {
        while (this.inputStream.available() > 0) {
            this.inputStream.read();
        }
    }

    private void checkReadResponse(final byte[] response, final int functionCode, final int expectedByteCount)
            throws ModbusProtocolException {
        if (response.length < 2 || (response[0] & 0xff) != functionCode) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE,
                    "Unexpected Modbus function code in response");
        }
        if (response.length < expectedByteCount + 2) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE,
                    "Short Modbus read response: expected " + expectedByteCount + " data byte(s), got "
                            + (response.length - 2));
        }
        if ((response[1] & 0xff) != expectedByteCount) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE,
                    "Unexpected Modbus byte count " + (response[1] & 0xff) + ", expected " + expectedByteCount);
        }
    }

    private static void checkException(final byte[] pdu) throws ModbusProtocolException {
        if (pdu.length > 0 && (pdu[0] & 0x80) != 0) {
            final int exception = pdu.length > 1 ? pdu[1] & 0xff : -1;
            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Modbus exception response " + exception);
        }
    }

    private static void verifyEcho(final byte[] command, final byte[] response) throws ModbusProtocolException {
        if (response.length < 5) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE, "Short Modbus write echo");
        }
        for (int i = 1; i <= 5; i++) {
            if (command[i] != response[i - 1]) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE,
                        "Modbus write echo does not match the request");
            }
        }
    }

    private static boolean[] unpackBooleans(final byte[] response, final int count) {
        final boolean[] values = new boolean[count];
        for (int index = 0; index < count; index++) {
            values[index] = (response[2 + index / 8] & 1 << index % 8) != 0;
        }
        return values;
    }

    private static byte[] buildMultipleWriteCommand(final int unitAddr, final int functionCode, final int dataAddress,
            final int count, final int dataLength) {
        final byte[] cmd = new byte[dataLength + 7];
        cmd[0] = (byte) unitAddr;
        cmd[1] = (byte) functionCode;
        cmd[2] = high(dataAddress);
        cmd[3] = low(dataAddress);
        cmd[4] = high(count);
        cmd[5] = low(count);
        cmd[6] = (byte) dataLength;
        return cmd;
    }

    private static void verifyCrc(final byte[] frame) throws ModbusProtocolException {
        if (frame.length < RTU_MINIMUM_LENGTH) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE, "Short Modbus RTU frame");
        }
        final int expected = Crc16.getCrc16(frame, frame.length - 2, 0xffff);
        final int actual = (frame[frame.length - 2] & 0xff) | (frame[frame.length - 1] & 0xff) << 8;
        if (expected != actual) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Bad Modbus RTU CRC");
        }
    }

    private static byte[] concat(final byte[] first, final byte[] second) {
        final byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte high(final int value) {
        return (byte) (value >> 8);
    }

    private static byte low(final int value) {
        return (byte) value;
    }

    private static void validateAddress(final int address) throws ModbusProtocolException {
        if (address < 0 || address > MAX_ADDRESS) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS,
                    "Invalid Modbus address " + address);
        }
    }

    private static void validateCount(final int count, final int max, final String kind)
            throws ModbusProtocolException {
        if (count < 1 || count > max) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS,
                    "Invalid Modbus " + kind + " count " + count);
        }
    }

    private static boolean isRetryable(final ModbusProtocolException e) {
        // 只重试传输层失败；异常响应/CRC/回显不符属于确定性协议错误，重试只会放大问题
        return e.getCode() == ModbusProtocolErrorCode.RESPONSE_TIMEOUT
                || e.getCode() == ModbusProtocolErrorCode.CONNECTION_FAILURE;
    }

    private int nextTransactionIndex() {
        this.transactionIndex = this.transactionIndex >= 0xffff ? 0 : this.transactionIndex + 1;
        return this.transactionIndex;
    }

    private void closeQuietly() {
        final Socket current = this.socket;
        this.socket = null;
        this.inputStream = null;
        this.outputStream = null;
        if (current != null) {
            try {
                current.close();
            } catch (final IOException e) {
                log.debug("Ignoring error while closing Modbus socket", e);
            }
        }
    }

    private static String require(final Properties config, final String key) {
        final String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Modbus connection property " + key);
        }
        return value;
    }

    private static String valueOrDefault(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parseRange(final String value, final int min, final int max, final String label) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, e);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Invalid " + label + ": " + parsed);
        }
        return parsed;
    }
}
