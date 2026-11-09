/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 测试用 Modbus 从站：只实现本测试需要的 01/02/03/04/05/06/15/16 功能码。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 Modbus 从站（测试用），支持 Modbus TCP 与 RTU over TCP。
 * <p>
 * 只实现驱动断言需要的最小子集；寄存器/线圈表按地址保存，便于测试读回刚写入的值。
 * </p>
 */
final class ModbusTestServer implements AutoCloseable {

    private static final int MAX_ADDRESS = 10000;

    private final ServerSocket serverSocket;

    private final Thread thread;

    private final ModbusTransmissionMode mode;

    private final Map<Integer, Integer> holdingRegisters = new ConcurrentHashMap<>();

    private final Map<Integer, Integer> inputRegisters = new ConcurrentHashMap<>();

    private final Set<Integer> coils = new ConcurrentSkipListSet<>();

    private final Set<Integer> discreteInputs = new ConcurrentSkipListSet<>();

    private final AtomicInteger requests = new AtomicInteger();

    private volatile boolean running = true;

    private volatile byte[] lastRequestPdu;

    private volatile boolean shortReads;

    private volatile boolean closeAfterRequest;

    ModbusTestServer(final ModbusTransmissionMode mode) throws IOException {
        this.mode = mode;
        this.serverSocket = new ServerSocket(0);
        this.thread = new Thread(this::acceptLoop, "modbus-test-slave");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    int port() {
        return this.serverSocket.getLocalPort();
    }

    int requestCount() {
        return this.requests.get();
    }

    byte[] lastRequestPdu() {
        return this.lastRequestPdu;
    }

    ModbusTestServer withHolding(final int address, final int value) {
        this.holdingRegisters.put(address, value);
        return this;
    }

    ModbusTestServer withInputRegister(final int address, final int value) {
        this.inputRegisters.put(address, value);
        return this;
    }

    ModbusTestServer withShortReads() {
        this.shortReads = true;
        return this;
    }

    ModbusTestServer withCloseAfterRequest() {
        this.closeAfterRequest = true;
        return this;
    }

    ModbusTestServer withCoil(final int address, final boolean value) {
        setCoil(this.coils, address, value);
        return this;
    }

    ModbusTestServer withDiscreteInput(final int address, final boolean value) {
        setCoil(this.discreteInputs, address, value);
        return this;
    }

    int holding(final int address) {
        return this.holdingRegisters.getOrDefault(address, 0);
    }

    boolean coil(final int address) {
        return this.coils.contains(address);
    }

    @Override
    public void close() throws IOException {
        this.running = false;
        this.serverSocket.close();
        try {
            this.thread.join(2000L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (this.running) {
            try (Socket socket = this.serverSocket.accept()) {
                serve(socket);
            } catch (final IOException e) {
                if (this.running) {
                    return;
                }
            }
        }
    }

    private void serve(final Socket socket) throws IOException {
        final DataInputStream input = new DataInputStream(socket.getInputStream());
        final OutputStream output = socket.getOutputStream();
        while (this.running) {
            try {
                final Frame request = readFrame(input);
                this.requests.incrementAndGet();
                this.lastRequestPdu = request.pdu();
                if (this.closeAfterRequest) {
                    socket.setSoLinger(true, 0);
                    return;
                }
                final byte[] response = handle(request.unit(), request.pdu());
                writeFrame(output, request, response);
            } catch (final EOFException e) {
                return;
            }
        }
    }

    private Frame readFrame(final DataInputStream input) throws IOException {
        if (this.mode == ModbusTransmissionMode.TCP) {
            final byte[] header = readFully(input, 7);
            final int transaction = (header[0] & 0xff) << 8 | header[1] & 0xff;
            final int length = (header[4] & 0xff) << 8 | header[5] & 0xff;
            final int unit = header[6] & 0xff;
            final byte[] pdu = readFully(input, length - 1);
            return new Frame(transaction, unit, pdu);
        }
        final byte[] head = readFully(input, 2);
        final int unit = head[0] & 0xff;
        final int function = head[1] & 0xff;
        final int pduLength = rtuPayloadLength(function);
        final byte[] rest = readFully(input, pduLength - 1 + 2);
        final byte[] pdu = new byte[pduLength];
        pdu[0] = head[1];
        System.arraycopy(rest, 0, pdu, 1, pduLength - 1);
        final byte[] frame = new byte[2 + rest.length];
        System.arraycopy(head, 0, frame, 0, 2);
        System.arraycopy(rest, 0, frame, 2, rest.length);
        final int expected = Crc16.getCrc16(frame, frame.length - 2, 0xffff);
        final int actual = (frame[frame.length - 2] & 0xff) | (frame[frame.length - 1] & 0xff) << 8;
        if (expected != actual) {
            throw new IOException("Bad CRC in test request");
        }
        return new Frame(0, unit, pdu);
    }

    private static int rtuPayloadLength(final int function) throws IOException {
        switch (function) {
            case ModbusFunctionCodes.READ_COIL_STATUS:
            case ModbusFunctionCodes.READ_INPUT_STATUS:
            case ModbusFunctionCodes.READ_HOLDING_REGS:
            case ModbusFunctionCodes.READ_INPUT_REGS:
            case ModbusFunctionCodes.FORCE_SINGLE_COIL:
            case ModbusFunctionCodes.PRESET_SINGLE_REG:
                return 5;
            case ModbusFunctionCodes.FORCE_MULTIPLE_COILS:
            case ModbusFunctionCodes.PRESET_MULTIPLE_REGS:
                // 由调用方再读 byte count；这里用 5 会少读，故需要单独处理
                throw new IOException("Unsupported RTU multi-write test frame");
            default:
                throw new IOException("Unsupported RTU function " + function);
        }
    }

    private void writeFrame(final OutputStream output, final Frame request, final byte[] pdu) throws IOException {
        if (this.mode == ModbusTransmissionMode.TCP) {
            final byte[] frame = new byte[7 + pdu.length];
            frame[0] = (byte) (request.transaction() >> 8);
            frame[1] = (byte) request.transaction();
            frame[2] = 0;
            frame[3] = 0;
            final int length = pdu.length + 1;
            frame[4] = (byte) (length >> 8);
            frame[5] = (byte) length;
            frame[6] = (byte) request.unit();
            System.arraycopy(pdu, 0, frame, 7, pdu.length);
            output.write(frame);
        } else {
            final byte[] body = new byte[1 + pdu.length];
            body[0] = (byte) request.unit();
            System.arraycopy(pdu, 0, body, 1, pdu.length);
            final int crc = Crc16.getCrc16(body, body.length, 0xffff);
            final byte[] frame = new byte[body.length + 2];
            System.arraycopy(body, 0, frame, 0, body.length);
            frame[body.length] = (byte) crc;
            frame[body.length + 1] = (byte) (crc >> 8);
            output.write(frame);
        }
        output.flush();
    }

    private byte[] handle(final int unit, final byte[] pdu) {
        final int function = pdu[0] & 0xff;
        try {
            switch (function) {
                case ModbusFunctionCodes.READ_COIL_STATUS:
                    return readBits(pdu, this.coils, function);
                case ModbusFunctionCodes.READ_INPUT_STATUS:
                    return readBits(pdu, this.discreteInputs, function);
                case ModbusFunctionCodes.READ_HOLDING_REGS:
                    return readRegisters(pdu, this.holdingRegisters, function);
                case ModbusFunctionCodes.READ_INPUT_REGS:
                    return readRegisters(pdu, this.inputRegisters, function);
                case ModbusFunctionCodes.FORCE_SINGLE_COIL:
                    return writeSingleCoil(pdu);
                case ModbusFunctionCodes.PRESET_SINGLE_REG:
                    return writeSingleRegister(pdu);
                case ModbusFunctionCodes.FORCE_MULTIPLE_COILS:
                    return writeMultipleCoils(pdu);
                case ModbusFunctionCodes.PRESET_MULTIPLE_REGS:
                    return writeMultipleRegisters(pdu);
                default:
                    return exception(function, 1);
            }
        } catch (final IllegalArgumentException e) {
            return exception(function, 2);
        }
    }

    private byte[] readBits(final byte[] pdu, final Set<Integer> values, final int function) {
        final int address = address(pdu);
        final int count = count(pdu);
        checkRange(address, count);
        final int byteCount = (count + 7) / 8;
        if (this.shortReads) {
            return new byte[] {(byte) function, (byte) byteCount};
        }
        final byte[] response = new byte[2 + byteCount];
        response[0] = (byte) function;
        response[1] = (byte) byteCount;
        for (int i = 0; i < count; i++) {
            if (values.contains(address + i)) {
                response[2 + i / 8] = (byte) (response[2 + i / 8] | 1 << i % 8);
            }
        }
        return response;
    }

    private byte[] readRegisters(final byte[] pdu, final Map<Integer, Integer> values, final int function) {
        final int address = address(pdu);
        final int count = count(pdu);
        checkRange(address, count);
        if (this.shortReads) {
            return new byte[] {(byte) function, (byte) (count * 2)};
        }
        final byte[] response = new byte[2 + count * 2];
        response[0] = (byte) function;
        response[1] = (byte) (count * 2);
        for (int i = 0; i < count; i++) {
            final int value = values.getOrDefault(address + i, 0);
            response[2 + i * 2] = (byte) (value >> 8);
            response[3 + i * 2] = (byte) value;
        }
        return response;
    }

    private byte[] writeSingleCoil(final byte[] pdu) {
        final int address = address(pdu);
        checkRange(address, 1);
        final int value = (pdu[3] & 0xff) << 8 | pdu[4] & 0xff;
        setCoil(this.coils, address, value == 0xff00);
        return echo(pdu);
    }

    private byte[] writeSingleRegister(final byte[] pdu) {
        final int address = address(pdu);
        checkRange(address, 1);
        this.holdingRegisters.put(address, (pdu[3] & 0xff) << 8 | pdu[4] & 0xff);
        return echo(pdu);
    }

    private byte[] writeMultipleCoils(final byte[] pdu) {
        final int address = address(pdu);
        final int count = count(pdu);
        checkRange(address, count);
        final int byteCount = pdu[5] & 0xff;
        for (int i = 0; i < count; i++) {
            final boolean value = (pdu[6 + i / 8] & 1 << i % 8) != 0;
            setCoil(this.coils, address + i, value);
        }
        if (byteCount != (count + 7) / 8) {
            throw new IllegalArgumentException("Bad coil byte count");
        }
        return echo(pdu);
    }

    private byte[] writeMultipleRegisters(final byte[] pdu) {
        final int address = address(pdu);
        final int count = count(pdu);
        checkRange(address, count);
        final int byteCount = pdu[5] & 0xff;
        if (byteCount != count * 2) {
            throw new IllegalArgumentException("Bad register byte count");
        }
        for (int i = 0; i < count; i++) {
            this.holdingRegisters.put(address + i, (pdu[6 + i * 2] & 0xff) << 8 | pdu[7 + i * 2] & 0xff);
        }
        return echo(pdu);
    }

    private static byte[] echo(final byte[] pdu) {
        return java.util.Arrays.copyOf(pdu, 5);
    }

    private static byte[] exception(final int function, final int code) {
        return new byte[] {(byte) (function | 0x80), (byte) code};
    }

    private static int address(final byte[] pdu) {
        return (pdu[1] & 0xff) << 8 | pdu[2] & 0xff;
    }

    private static int count(final byte[] pdu) {
        return (pdu[3] & 0xff) << 8 | pdu[4] & 0xff;
    }

    private static void checkRange(final int address, final int count) {
        if (address < 0 || count < 1 || address + count > MAX_ADDRESS) {
            throw new IllegalArgumentException("Address out of range");
        }
    }

    private static void setCoil(final Set<Integer> values, final int address, final boolean value) {
        if (value) {
            values.add(address);
        } else {
            values.remove(address);
        }
    }

    private static byte[] readFully(final DataInputStream input, final int length) throws IOException {
        final byte[] buffer = new byte[length];
        input.readFully(buffer);
        return buffer;
    }

    private record Frame(int transaction, int unit, byte[] pdu) {
    }
}
