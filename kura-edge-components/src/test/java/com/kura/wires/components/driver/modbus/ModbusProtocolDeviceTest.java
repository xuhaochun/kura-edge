/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 协议事务：TCP 与 RTU-over-TCP 的读写帧、异常响应和配置校验。 */
class ModbusProtocolDeviceTest {

    private ModbusProtocolDevice device;

    private ModbusTestServer server;

    @AfterEach
    public void tearDown() throws IOException {
        if (this.device != null) {
            this.device.disconnect();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    public void readHoldingRegistersOverModbusTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP).withHolding(10, 0x1234).withHolding(11, 0x5678);
        givenDevice(ModbusTransmissionMode.TCP);

        final int[] registers = this.device.readHoldingRegisters(1, 10, 2);

        assertThat(registers).containsExactly(0x1234, 0x5678);
    }

    @Test
    public void readInputRegistersOverModbusTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP).withInputRegister(0, 0x00FF);
        givenDevice(ModbusTransmissionMode.TCP);

        assertThat(this.device.readInputRegisters(1, 0, 1)).containsExactly(0x00FF);
    }

    @Test
    public void readCoilsAndDiscreteInputsOverModbusTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP).withCoil(0, true).withCoil(2, true).withDiscreteInput(1, true);
        givenDevice(ModbusTransmissionMode.TCP);

        assertThat(this.device.readCoils(1, 0, 3)).containsExactly(true, false, true);
        assertThat(this.device.readDiscreteInputs(1, 1, 1)).containsExactly(true);
    }

    @Test
    public void writeSingleCoilAndRegisterOverModbusTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP);
        givenDevice(ModbusTransmissionMode.TCP);

        this.device.writeSingleCoil(1, 3, true);
        this.device.writeSingleRegister(1, 4, 0xBEEF);

        assertThat(this.server.coil(3)).isTrue();
        assertThat(this.server.holding(4)).isEqualTo(0xBEEF);
    }

    @Test
    public void writeMultipleCoilsAndRegistersOverModbusTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP);
        givenDevice(ModbusTransmissionMode.TCP);

        this.device.writeMultipleCoils(1, 10, new boolean[] {true, false, true});
        this.device.writeMultipleRegister(1, 20, new int[] {0x1111, 0x2222});

        assertThat(this.server.coil(10)).isTrue();
        assertThat(this.server.coil(11)).isFalse();
        assertThat(this.server.coil(12)).isTrue();
        assertThat(this.server.holding(20)).isEqualTo(0x1111);
        assertThat(this.server.holding(21)).isEqualTo(0x2222);
    }

    @Test
    public void readHoldingRegistersOverRtuTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP_RTU).withHolding(0, 0xCAFE);
        givenDevice(ModbusTransmissionMode.TCP_RTU);

        assertThat(this.device.readHoldingRegisters(1, 0, 1)).containsExactly(0xCAFE);
    }

    @Test
    public void writeSingleCoilAndRegisterOverRtuTcp() throws Exception {
        givenServer(ModbusTransmissionMode.TCP_RTU);
        givenDevice(ModbusTransmissionMode.TCP_RTU);

        this.device.writeSingleCoil(1, 5, true);
        this.device.writeSingleRegister(1, 6, 0x0A0B);

        assertThat(this.server.coil(5)).isTrue();
        assertThat(this.server.holding(6)).isEqualTo(0x0A0B);
    }

    @Test
    public void exposeExceptionResponseAsProtocolError() throws Exception {
        givenServer(ModbusTransmissionMode.TCP);
        givenDevice(ModbusTransmissionMode.TCP);

        assertThatThrownBy(() -> this.device.readHoldingRegisters(1, 9999, 2))
                .isInstanceOf(ModbusProtocolException.class)
                .hasMessageContaining("exception");
    }

    @Test
    public void rejectInvalidConfiguration() {
        final ModbusProtocolDevice candidate = new ModbusProtocolDevice();

        assertThatThrownBy(() -> candidate.configureConnection(new Properties()))
                .isInstanceOf(ModbusProtocolException.class)
                .extracting("code").isEqualTo(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void rejectOutOfRangeConnectionValues() {
        final ModbusProtocolDevice candidate = new ModbusProtocolDevice();
        final Properties config = connectionConfig(ModbusTransmissionMode.TCP);
        config.setProperty("ethport", "0");

        assertThatThrownBy(() -> candidate.configureConnection(config))
                .isInstanceOf(ModbusProtocolException.class)
                .extracting("code").isEqualTo(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void rejectTransactionBeforeConfiguration() {
        final ModbusProtocolDevice candidate = new ModbusProtocolDevice();

        assertThatThrownBy(() -> candidate.readHoldingRegisters(1, 0, 1))
                .isInstanceOf(ModbusProtocolException.class)
                .extracting("code").isEqualTo(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void reportConnectionFailureWhenPortIsClosed() throws Exception {
        // 先起一个服务器拿到端口再关掉，保证端口上一定没有监听
        final ModbusTestServer closed = new ModbusTestServer(ModbusTransmissionMode.TCP);
        final int closedPort = closed.port();
        closed.close();
        this.device = new ModbusProtocolDevice();
        this.device.configureConnection(connectionConfig(ModbusTransmissionMode.TCP, closedPort));

        assertThatThrownBy(() -> this.device.readHoldingRegisters(1, 0, 1))
                .isInstanceOf(ModbusProtocolException.class)
                .extracting("code").isEqualTo(ModbusProtocolErrorCode.CONNECTION_FAILURE);
    }

    @Test
    public void rejectInvalidAddressAndCountLocally() throws Exception {
        givenServer(ModbusTransmissionMode.TCP);
        givenDevice(ModbusTransmissionMode.TCP);

        assertThatThrownBy(() -> this.device.readHoldingRegisters(1, 0, 0))
                .isInstanceOf(ModbusProtocolException.class)
                .extracting("code").isEqualTo(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS);
        assertThatThrownBy(() -> this.device.readCoils(1, 70000, 1))
                .isInstanceOf(ModbusProtocolException.class)
                .extracting("code").isEqualTo(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS);
    }

    private ModbusTestServer givenServer(final ModbusTransmissionMode mode) throws IOException {
        this.server = new ModbusTestServer(mode);
        return this.server;
    }

    private void givenDevice(final ModbusTransmissionMode mode) throws ModbusProtocolException {
        this.device = new ModbusProtocolDevice();
        this.device.configureConnection(connectionConfig(mode, this.server.port()));
    }

    private static Properties connectionConfig(final ModbusTransmissionMode mode) {
        return connectionConfig(mode, 502);
    }

    private static Properties connectionConfig(final ModbusTransmissionMode mode, final int port) {
        final Properties config = new Properties();
        config.setProperty("connectionType", mode == ModbusTransmissionMode.TCP ? "TCP/IP" : "TCP-RTU");
        config.setProperty("ipAddress", "127.0.0.1");
        config.setProperty("ethport", String.valueOf(port));
        config.setProperty("unitId", "1");
        config.setProperty("respTimeout", "1000");
        config.setProperty("maxRetries", "0");
        return config;
    }
}
