/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.driverhost.modbus.ModbusChannelConfig.ObjectType;
import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import com.kura.wires.model.type.TypedValue;

/**
 * Modbus 驱动：把资产通道记录映射到官方 Modbus 主站功能码（01/02/03/04/05/06/15/16）。
 * <p>
 * 驱动实例配置（驱动 OCD）决定连接、unit id、超时与重试；通道配置
 * （{@link ModbusChannelDescriptor}）决定对象类型、地址、数量与字节序。读：按通道
 * {@code +value.type} 解码成对应 Java 值；写：把值编码成寄存器/位后下发。
 * </p>
 * <p>
 * 失败语义：单条记录的配置/协议错误只把该记录标成 {@code FAILURE}，其余记录继续；
 * 连接建立失败或连接级错误抛 {@link Driver.ConnectionException}，由资产层统一转成通道 IO 失败。
 * </p>
 */
public final class ModbusDriver implements Driver {

    private static final Logger log = LoggerFactory.getLogger(ModbusDriver.class);

    private static final String DEFAULT_CONNECTION_TYPE = "TCP/IP";

    private static final String DEFAULT_IP_ADDRESS = "127.0.0.1";

    private static final int DEFAULT_TCP_PORT = 502;

    private final String pid;

    private final Map<String, Object> properties;

    private final ModbusProtocolDevice device = new ModbusProtocolDevice();

    private volatile boolean connected;

    private int unitId = 1;

    /**
     * 创建 Modbus 驱动实例。
     *
     * @param pid 驱动实例 PID
     * @param properties 驱动实例配置
     */
    public ModbusDriver(final String pid, final Map<String, Object> properties) {
        this.pid = pid;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /**
     * 返回驱动实例 PID。
     *
     * @return PID
     */
    public String getPid() {
        return this.pid;
    }

    @Override
    public void connect() throws ConnectionException {
        if (this.connected) {
            return;
        }
        try {
            this.device.configureConnection(connectionProperties());
            this.unitId = integerProperty(ModbusDriverFactory.PROP_UNIT_ID, 1);
            this.device.connect();
            this.connected = true;
            log.info("Modbus driver {} connected to {}:{}", this.pid,
                    this.properties.get(ModbusDriverFactory.PROP_IP_ADDRESS),
                    this.properties.get(ModbusDriverFactory.PROP_TCP_PORT));
        } catch (final ModbusProtocolException | IllegalArgumentException e) {
            this.connected = false;
            throw new ConnectionException(e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() throws ConnectionException {
        this.device.disconnect();
        this.connected = false;
    }

    @Override
    public ChannelDescriptor getChannelDescriptor() {
        return new ModbusChannelDescriptor();
    }

    @Override
    public void read(final List<ChannelRecord> records) throws ConnectionException {
        ensureConnected();
        for (final ChannelRecord record : records) {
            try {
                final ModbusChannelConfig config = ModbusChannelConfig.from(record.getChannelConfig(),
                        record.getValueType());
                record.setValue(readValue(config, record));
                succeed(record);
            } catch (final ModbusProtocolException e) {
                if (isConnectionLevel(e)) {
                    dropConnection();
                    throw new ConnectionException(e.getMessage(), e);
                }
                fail(record, e);
            } catch (final IllegalArgumentException e) {
                fail(record, e);
            }
        }
    }

    @Override
    public void write(final List<ChannelRecord> records) throws ConnectionException {
        ensureConnected();
        for (final ChannelRecord record : records) {
            try {
                final ModbusChannelConfig config = ModbusChannelConfig.from(record.getChannelConfig(),
                        record.getValueType());
                writeValue(config, record);
                succeed(record);
            } catch (final ModbusProtocolException e) {
                if (isConnectionLevel(e)) {
                    dropConnection();
                    throw new ConnectionException(e.getMessage(), e);
                }
                fail(record, e);
            } catch (final IllegalArgumentException e) {
                fail(record, e);
            }
        }
    }

    @Override
    public void registerChannelListener(final Map<String, Object> channelConfig, final ChannelListener listener) {
        log.debug("Modbus driver {} does not support channel listeners yet", this.pid);
    }

    @Override
    public void unregisterChannelListener(final ChannelListener listener) {
        log.debug("Modbus driver {} does not support channel listeners yet", this.pid);
    }

    private TypedValue<?> readValue(final ModbusChannelConfig config, final ChannelRecord record)
            throws ModbusProtocolException {
        if (config.getObjectType().isBit()) {
            final boolean[] bits = config.getObjectType() == ObjectType.COIL
                    ? this.device.readCoils(this.unitId, config.getAddress(), config.getRegisterCount())
                    : this.device.readDiscreteInputs(this.unitId, config.getAddress(), config.getRegisterCount());
            return ModbusValueCodec.decodeBits(bits, record.getValueType(), config.getRegisterCount());
        }
        final int[] registers = config.getObjectType() == ObjectType.HOLDING_REGISTER
                ? this.device.readHoldingRegisters(this.unitId, config.getAddress(), config.getRegisterCount())
                : this.device.readInputRegisters(this.unitId, config.getAddress(), config.getRegisterCount());
        return ModbusValueCodec.decodeRegisters(registers, record.getValueType(), config.getRegisterCount(),
                config.getDataOrder());
    }

    private void writeValue(final ModbusChannelConfig config, final ChannelRecord record)
            throws ModbusProtocolException {
        if (!config.getObjectType().isWritable()) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.METHOD_NOT_SUPPORTED,
                    "Modbus object " + config.getObjectType() + " is read-only");
        }
        if (config.getObjectType().isBit()) {
            final boolean[] bits = ModbusValueCodec.encodeBits(record.getValue(), record.getValueType(),
                    config.getRegisterCount());
            if (bits.length == 1) {
                this.device.writeSingleCoil(this.unitId, config.getAddress(), bits[0]);
            } else {
                this.device.writeMultipleCoils(this.unitId, config.getAddress(), bits);
            }
            return;
        }
        final int[] registers = ModbusValueCodec.encodeRegisters(record.getValue(), record.getValueType(),
                config.getRegisterCount(), config.getDataOrder());
        if (registers.length == 1) {
            this.device.writeSingleRegister(this.unitId, config.getAddress(), registers[0]);
        } else {
            this.device.writeMultipleRegister(this.unitId, config.getAddress(), registers);
        }
    }

    /**
     * 连接级失败后丢弃本地连接状态：半开 socket 留着只会让后续请求继续失败（对照上游
     * {@code Driver} 契约"连接中断时应清理该连接相关资源"）。
     */
    private void dropConnection() {
        this.connected = false;
        try {
            this.device.disconnect();
        } catch (final RuntimeException e) {
            log.debug("Modbus driver {} failed to close broken connection", this.pid, e);
        }
    }

    private void ensureConnected() throws ConnectionException {
        if (!this.connected) {
            connect();
        }
    }

    private Properties connectionProperties() {
        final Properties result = new Properties();
        result.setProperty("connectionType", stringProperty(ModbusDriverFactory.PROP_CONNECTION_TYPE,
                DEFAULT_CONNECTION_TYPE));
        result.setProperty("ipAddress", stringProperty(ModbusDriverFactory.PROP_IP_ADDRESS, DEFAULT_IP_ADDRESS));
        result.setProperty("ethport", stringProperty(ModbusDriverFactory.PROP_TCP_PORT, String.valueOf(DEFAULT_TCP_PORT)));
        result.setProperty("unitId", stringProperty(ModbusDriverFactory.PROP_UNIT_ID, "1"));
        result.setProperty("respTimeout", stringProperty(ModbusDriverFactory.PROP_RESPONSE_TIMEOUT, "1000"));
        result.setProperty("maxRetries", stringProperty(ModbusDriverFactory.PROP_MAX_RETRIES, "2"));
        return result;
    }

    private String stringProperty(final String key, final String fallback) {
        final Object value = this.properties.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private int integerProperty(final String key, final int fallback) {
        final Object value = this.properties.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + key + ": " + value, e);
        }
    }

    private static boolean isConnectionLevel(final ModbusProtocolException e) {
        return e.getCode() == ModbusProtocolErrorCode.CONNECTION_FAILURE
                || e.getCode() == ModbusProtocolErrorCode.NOT_CONNECTED;
    }

    private static void succeed(final ChannelRecord record) {
        record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
        record.setTimestamp(System.currentTimeMillis());
    }

    private static void fail(final ChannelRecord record, final Exception exception) {
        record.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, exception.getMessage(), exception));
        record.setTimestamp(System.currentTimeMillis());
    }
}
