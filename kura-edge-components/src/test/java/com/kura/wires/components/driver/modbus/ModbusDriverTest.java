/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.driver.Driver;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValues;

/** Modbus 驱动：记录级读写、部分失败继续、只读对象拒写与连接异常。 */
class ModbusDriverTest {

    private final ModbusChannelDescriptor descriptor = new ModbusChannelDescriptor();

    private ModbusTestServer server;

    private ModbusDriver driver;

    @AfterEach
    public void tearDown() throws IOException, Driver.ConnectionException {
        if (this.driver != null) {
            this.driver.disconnect();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    public void readRegisterAndBitChannels() throws Exception {
        givenServer().withHolding(0, 0x1234).withInputRegister(2, 77).withCoil(3, true).withDiscreteInput(4, true);
        givenDriver();

        final ChannelRecord register = readRecord("temperature", DataType.INTEGER, channel("HOLDING_REGISTER", 0, null));
        final ChannelRecord input = readRecord("pressure", DataType.INTEGER, channel("INPUT_REGISTER", 2, null));
        final ChannelRecord coil = readRecord("enabled", DataType.BOOLEAN, channel("COIL", 3, null));
        final ChannelRecord discrete = readRecord("door", DataType.BOOLEAN, channel("DISCRETE_INPUT", 4, null));

        this.driver.read(List.of(register, input, coil, discrete));

        assertThat(register.getValue()).isEqualTo(TypedValues.newIntegerValue(0x1234));
        assertThat(input.getValue()).isEqualTo(TypedValues.newIntegerValue(77));
        assertThat(coil.getValue()).isEqualTo(TypedValues.newBooleanValue(true));
        assertThat(discrete.getValue()).isEqualTo(TypedValues.newBooleanValue(true));
        assertThat(register.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.SUCCESS);
    }

    @Test
    public void readMultiRegisterFloatAndLongChannels() throws Exception {
        givenServer().withHolding(10, 0x3FC0).withHolding(11, 0).withHolding(20, 0x0000)
                .withHolding(21, 0x002A);
        givenDriver();

        final ChannelRecord floatRecord = readRecord("flow", DataType.FLOAT,
                channel("HOLDING_REGISTER", 10, 2));
        final ChannelRecord longRecord = readRecord("counter", DataType.LONG,
                channel("HOLDING_REGISTER", 20, 2));

        this.driver.read(List.of(floatRecord, longRecord));

        assertThat(floatRecord.getValue()).isEqualTo(TypedValues.newFloatValue(1.5f));
        assertThat(longRecord.getValue()).isEqualTo(TypedValues.newLongValue(42L));
    }

    @Test
    public void markOnlyInvalidRecordAsFailure() throws Exception {
        givenServer().withHolding(0, 5);
        givenDriver();
        final ChannelRecord valid = readRecord("valid", DataType.INTEGER, channel("HOLDING_REGISTER", 0, null));
        final ChannelRecord invalid = readRecord("invalid", DataType.INTEGER, channel("NOPE", 0, null));

        this.driver.read(List.of(valid, invalid));

        assertThat(valid.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.SUCCESS);
        assertThat(invalid.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.FAILURE);
        assertThat(invalid.getChannelStatus().getExceptionMessage()).contains("NOPE");
    }

    @Test
    public void writeSingleAndMultipleRegisters() throws Exception {
        givenServer();
        givenDriver();

        final ChannelRecord single = writeRecord("setpoint", DataType.INTEGER, TypedValues.newIntegerValue(0xBEEF),
                channel("HOLDING_REGISTER", 0, null));
        final ChannelRecord multiple = writeRecord("calibration", DataType.LONG,
                TypedValues.newLongValue(0x12345678L), channel("HOLDING_REGISTER", 10, 2));

        this.driver.write(List.of(single, multiple));

        assertThat(this.server.holding(0)).isEqualTo(0xBEEF);
        assertThat(this.server.holding(10)).isEqualTo(0x1234);
        assertThat(this.server.holding(11)).isEqualTo(0x5678);
        assertThat(single.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.SUCCESS);
    }

    @Test
    public void writeCoilsAndByteArrayBits() throws Exception {
        givenServer();
        givenDriver();

        final ChannelRecord single = writeRecord("relay", DataType.BOOLEAN, TypedValues.newBooleanValue(true),
                channel("COIL", 0, null));
        final ChannelRecord packed = writeRecord("bank", DataType.BYTE_ARRAY,
                TypedValues.newByteArrayValue(new byte[] {0x05}), channel("COIL", 10, 3));

        this.driver.write(List.of(single, packed));

        assertThat(this.server.coil(0)).isTrue();
        assertThat(this.server.coil(10)).isTrue();
        assertThat(this.server.coil(11)).isFalse();
        assertThat(this.server.coil(12)).isTrue();
    }

    @Test
    public void markReadOnlyObjectWriteAsFailure() throws Exception {
        givenServer();
        givenDriver();
        final ChannelRecord input = writeRecord("input", DataType.INTEGER, TypedValues.newIntegerValue(1),
                channel("INPUT_REGISTER", 0, null));

        this.driver.write(List.of(input));

        assertThat(input.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.FAILURE);
        assertThat(input.getChannelStatus().getExceptionMessage()).contains("read-only");
    }

    @Test
    public void exposeConnectionFailureAsDriverException() throws Exception {
        final ModbusTestServer closed = new ModbusTestServer(ModbusTransmissionMode.TCP);
        final int port = closed.port();
        closed.close();
        this.driver = new ModbusDriver("modbus", properties(port));

        assertThatThrownBy(() -> this.driver.read(List.of(readRecord("x", DataType.INTEGER,
                channel("HOLDING_REGISTER", 0, null)))))
                .isInstanceOf(Driver.ConnectionException.class);
    }

    @Test
    public void exposeChannelDescriptorAndIgnoreListeners() {
        final ModbusDriverListener listener = new ModbusDriverListener();

        assertThat(this.descriptor.getDescriptor()).isInstanceOf(List.class);
        new ModbusDriver("modbus", Map.of()).registerChannelListener(Map.of(), listener);
        new ModbusDriver("modbus", Map.of()).unregisterChannelListener(listener);
        assertThat(new ModbusDriver("modbus", Map.of()).getChannelDescriptor())
                .isInstanceOf(ModbusChannelDescriptor.class);
        assertThat(new ModbusDriver("modbus", null).getPid()).isEqualTo("modbus");
    }

    private ModbusTestServer givenServer() throws IOException {
        this.server = new ModbusTestServer(ModbusTransmissionMode.TCP);
        return this.server;
    }

    private void givenDriver() throws Driver.ConnectionException {
        this.driver = new ModbusDriver("modbus", properties(this.server.port()));
    }

    private static Map<String, Object> properties(final int port) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ModbusDriverFactory.PROP_CONNECTION_TYPE, "TCP/IP");
        properties.put(ModbusDriverFactory.PROP_IP_ADDRESS, "127.0.0.1");
        properties.put(ModbusDriverFactory.PROP_TCP_PORT, port);
        properties.put(ModbusDriverFactory.PROP_UNIT_ID, 1);
        properties.put(ModbusDriverFactory.PROP_RESPONSE_TIMEOUT, 1000);
        properties.put(ModbusDriverFactory.PROP_MAX_RETRIES, 0);
        return properties;
    }

    private static ChannelRecord readRecord(final String name, final DataType type, final Map<String, Object> config) {
        final ChannelRecord record = ChannelRecord.createReadRecord(name, type);
        record.setChannelConfig(config);
        return record;
    }

    private static ChannelRecord writeRecord(final String name, final DataType type,
            final com.kura.wires.model.type.TypedValue<?> value, final Map<String, Object> config) {
        final ChannelRecord record = ChannelRecord.createWriteRecord(name, type, value);
        record.setChannelConfig(config);
        return record;
    }

    private static Map<String, Object> channel(final String objectType, final int address, final Integer count) {
        final Map<String, Object> config = new LinkedHashMap<>();
        config.put(ModbusChannelConfig.OBJECT_TYPE, objectType);
        config.put(ModbusChannelConfig.ADDRESS, address);
        if (count != null) {
            config.put(ModbusChannelConfig.REGISTER_COUNT, count);
        }
        return config;
    }

    private static final class ModbusDriverListener implements ChannelListener {

        @Override
        public void onChannelEvent(final com.kura.wires.model.channel.listener.ChannelEvent event) {
            // 监听器测试不关心事件内容
        }
    }
}
