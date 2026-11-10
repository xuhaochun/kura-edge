/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.simulated;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;

/**
 * 模拟驱动：用内存寄存器表冒充一台设备。
 * <p>
 * <b>这是本移植新增的驱动</b>（上游没有可用的协议驱动实现：modbus/opcua/gpio 都在未纳入的独立仓库里）。
 * 它让 Drivers and Assets 的「读值 / 写值」链路可运行、可验收，也让后续接真实驱动时有一份可参照的
 * {@link Driver} 实现。
 * </p>
 * <p>
 * 寄存器表按「实例」隔离（一个驱动实例 = 一台假设备），键是通道参数 {@code simulated.register}。
 * 读：寄存器有值则按通道读取类型转换后返回，否则返回该类型的零值；写：把值存进寄存器。
 * 读写的每条记录都会被标记为 SUCCESS。
 * </p>
 */
public final class SimulatedDriver implements Driver {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDriver.class);

    private static final String REGISTER_PARAM = SimulatedChannelDescriptor.REGISTER_PARAM;

    private final String pid;

    private final Map<String, Object> properties;

    private final Map<String, TypedValue<?>> registers = new ConcurrentHashMap<>();

    private volatile boolean connected;

    /**
     * 创建模拟驱动实例。
     *
     * @param pid 驱动实例 PID
     * @param properties 驱动实例配置
     */
    public SimulatedDriver(final String pid, final Map<String, Object> properties) {
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

    /**
     * 返回驱动实例配置。
     *
     * @return 配置属性
     */
    public Map<String, Object> getProperties() {
        return this.properties;
    }

    @Override
    public void connect() {
        this.connected = true;
        log.debug("Simulated driver {} connected", this.pid);
    }

    @Override
    public void disconnect() {
        this.connected = false;
        log.debug("Simulated driver {} disconnected", this.pid);
    }

    /**
     * 是否已连接。
     *
     * @return 已连接时为 true
     */
    public boolean isConnected() {
        return this.connected;
    }

    @Override
    public ChannelDescriptor getChannelDescriptor() {
        return new SimulatedChannelDescriptor();
    }

    @Override
    public void read(final List<ChannelRecord> records) throws ConnectionException {
        ensureConnected();
        for (final ChannelRecord record : records) {
            final String register = registerOf(record);
            final TypedValue<?> stored = this.registers.get(register);
            final TypedValue<?> value = stored == null ? zeroValueOf(record.getValueType()) : convert(stored, record);
            record.setValue(value);
            record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
            record.setTimestamp(System.currentTimeMillis());
        }
    }

    @Override
    public void write(final List<ChannelRecord> records) throws ConnectionException {
        ensureConnected();
        for (final ChannelRecord record : records) {
            this.registers.put(registerOf(record), record.getValue());
            record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
            record.setTimestamp(System.currentTimeMillis());
        }
    }

    @Override
    public void registerChannelListener(final Map<String, Object> channelConfig, final ChannelListener listener) {
        log.debug("Simulated driver {} does not emit channel events", this.pid);
    }

    @Override
    public void unregisterChannelListener(final ChannelListener listener) {
        log.debug("Simulated driver {} does not emit channel events", this.pid);
    }

    private void ensureConnected() throws ConnectionException {
        if (!this.connected) {
            connect();
        }
    }

    private static String registerOf(final ChannelRecord record) {
        final Map<String, Object> config = record.getChannelConfig();
        final Object register = config == null ? null : config.get(REGISTER_PARAM);
        return register == null ? SimulatedChannelDescriptor.DEFAULT_REGISTER : String.valueOf(register);
    }

    private static TypedValue<?> convert(final TypedValue<?> stored, final ChannelRecord record) {
        final Object raw = stored.getValue();
        if (raw == null) {
            return zeroValueOf(record.getValueType());
        }
        if (valueMatches(record.getValueType(), raw)) {
            return stored;
        }
        if (raw instanceof final Number number) {
            return forNumber(record.getValueType(), number);
        }
        return TypedValues.parseTypedValue(record.getValueType(), String.valueOf(raw));
    }

    private static boolean valueMatches(final DataType type, final Object raw) {
        switch (type) {
            case BOOLEAN:
                return raw instanceof Boolean;
            case BYTE_ARRAY:
                return raw instanceof byte[];
            case DOUBLE:
                return raw instanceof Double;
            case INTEGER:
                return raw instanceof Integer;
            case LONG:
                return raw instanceof Long;
            case FLOAT:
                return raw instanceof Float;
            case STRING:
                return raw instanceof String;
            default:
                return false;
        }
    }

    private static TypedValue<?> forNumber(final DataType type, final Number number) {
        switch (type) {
            case DOUBLE:
                return TypedValues.newDoubleValue(number.doubleValue());
            case INTEGER:
                return TypedValues.newIntegerValue(number.intValue());
            case LONG:
                return TypedValues.newLongValue(number.longValue());
            case FLOAT:
                return TypedValues.newFloatValue(number.floatValue());
            case STRING:
                return TypedValues.newStringValue(String.valueOf(number));
            default:
                return zeroValueOf(type);
        }
    }

    private static TypedValue<?> zeroValueOf(final DataType type) {
        switch (type) {
            case BOOLEAN:
                return TypedValues.newBooleanValue(false);
            case BYTE_ARRAY:
                return TypedValues.newByteArrayValue(new byte[0]);
            case DOUBLE:
                return TypedValues.newDoubleValue(0.0d);
            case INTEGER:
                return TypedValues.newIntegerValue(0);
            case LONG:
                return TypedValues.newLongValue(0L);
            case FLOAT:
                return TypedValues.newFloatValue(0.0f);
            case STRING:
                return TypedValues.newStringValue("");
            default:
                return TypedValues.newStringValue("");
        }
    }
}
