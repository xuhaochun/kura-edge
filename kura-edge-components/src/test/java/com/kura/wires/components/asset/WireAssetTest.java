/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.core.driver.DriverService;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireAsset 的行为测试：收到信封时「先读后写」、信封属性命名、类型不匹配静默忽略、
 * 变更去重与连接错误信封。
 */
public class WireAssetTest {

    private static final String DRIVER_PID = "driver-1";
    private static final String ASSET_PID = "asset-1";

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<WireEnvelope> emitted = new ArrayList<>();
    private StubDriver driver;
    private WireAsset asset;

    @BeforeEach
    public void setUp() {
        this.emitted.clear();
        this.driver = new StubDriver();
        final DriverService driverService = new StubDriverService(this.driver);
        final WireEmitContext emitContext = this.emitted::add;
        this.properties.put("driver.pid", DRIVER_PID);
        this.asset = new WireAsset(ASSET_PID, this.properties, emitContext, driverService);
    }

    @Test
    public void emitReadChannelsOnInboundEnvelope() {
        givenReadChannel("temp", DataType.DOUBLE);
        givenAsset();
        this.driver.readable.put("temp", TypedValues.newDoubleValue(21.5d));

        this.asset.onWireReceive(envelopeWith());

        assertThat(this.emitted).hasSize(1);
        final Map<String, TypedValue<?>> emittedProperties = this.emitted.get(0).getRecords().get(0).getProperties();
        assertThat(emittedProperties).containsEntry("assetName", TypedValues.newStringValue(ASSET_PID));
        assertThat(emittedProperties.get("temp").getValue()).isEqualTo(21.5d);
        assertThat(emittedProperties).containsKey("temp_timestamp");
    }

    @Test
    public void writeChannelValueMatchingChannelNameAndType() {
        givenWritableChannel("setpoint", DataType.INTEGER);
        givenAsset();

        this.asset.onWireReceive(envelopeWith(record("setpoint", TypedValues.newIntegerValue(9))));

        assertThat(this.driver.written).hasSize(1);
        assertThat(this.driver.written.get(0).getValue().getValue()).isEqualTo(9);
    }

    @Test
    public void ignoreRecordWhoseTypeDoesNotMatchChannel() {
        givenWritableChannel("setpoint", DataType.INTEGER);
        givenAsset();

        this.asset.onWireReceive(envelopeWith(record("setpoint", TypedValues.newStringValue("9"))));

        assertThat(this.driver.written).isEmpty();
    }

    @Test
    public void ignoreRecordForReadOnlyChannel() {
        givenReadChannel("temp", DataType.DOUBLE);
        givenAsset();

        this.asset.onWireReceive(envelopeWith(record("temp", TypedValues.newDoubleValue(1.0d))));

        assertThat(this.driver.written).isEmpty();
    }

    @Test
    public void emitOnlyChangedValuesWhenEmitOnChangeIsEnabled() {
        givenReadChannel("temp", DataType.DOUBLE);
        this.properties.put(WireAssetOptions.EMIT_ON_CHANGE_PROP_NAME, true);
        givenAsset();
        this.driver.readable.put("temp", TypedValues.newDoubleValue(1.0d));

        this.asset.onWireReceive(envelopeWith());
        this.asset.onWireReceive(envelopeWith());

        assertThat(this.emitted).hasSize(1);
    }

    @Test
    public void emitChannelErrorPropertyWhenEmitErrorsIsEnabled() {
        givenReadChannel("temp", DataType.DOUBLE);
        this.properties.put(WireAssetOptions.EMIT_ERRORS_PROP_NAME, true);
        givenAsset();
        this.driver.failingChannels.add("temp");

        this.asset.onWireReceive(envelopeWith());

        final Map<String, TypedValue<?>> emittedProperties = this.emitted.get(0).getRecords().get(0).getProperties();
        assertThat(emittedProperties.get("temp_error").getValue()).isEqualTo("read failed");
        assertThat(emittedProperties).doesNotContainKey("temp");
    }

    @Test
    public void emitAssetErrorOnConnectionFailureWhenBothFlagsAreEnabled() {
        givenReadChannel("temp", DataType.DOUBLE);
        this.properties.put(WireAssetOptions.EMIT_ERRORS_PROP_NAME, true);
        this.properties.put(WireAssetOptions.EMIT_CONNECTION_ERRORS_PROP_NAME, true);
        givenAsset();
        this.driver.failRead = true;

        this.asset.onWireReceive(envelopeWith());

        final Map<String, TypedValue<?>> emittedProperties = this.emitted.get(0).getRecords().get(0).getProperties();
        assertThat(emittedProperties).containsKey("assetError");
        assertThat(emittedProperties).containsEntry("assetName", TypedValues.newStringValue(ASSET_PID));
    }

    @Test
    public void doNotEmitErrorEnvelopeWhenConnectionErrorsFlagIsDisabled() {
        givenReadChannel("temp", DataType.DOUBLE);
        this.properties.put(WireAssetOptions.EMIT_ERRORS_PROP_NAME, true);
        givenAsset();
        this.driver.failRead = true;

        this.asset.onWireReceive(envelopeWith());

        assertThat(this.emitted).isEmpty();
    }

    @Test
    public void fallBackToPerChannelTimestampModeOnUnknownValue() {
        this.properties.put(WireAssetOptions.TIMESTAMP_MODE_PROP_NAME, "NOT_A_MODE");

        givenAsset();

        assertThat(this.asset.getOptions().getTimestampMode()).isEqualTo(TimestampMode.PER_CHANNEL);
    }

    @Test
    public void treatStringTrueAsDisabledForBooleanEmitOptions() {
        this.properties.put(WireAssetOptions.EMIT_ERRORS_PROP_NAME, "true");

        givenAsset();

        assertThat(this.asset.getOptions().emitErrors()).isFalse();
        assertThat(this.asset.getOptions().emitEmptyEnvelopes()).isTrue();
    }

    @Test
    public void suppressEmptyEnvelopesWhenConfiguredAndNothingWasRead() {
        this.properties.put(WireAssetOptions.EMIT_EMPTY_ENVELOPES_PROP_NAME, false);
        givenAsset();

        this.asset.onWireReceive(envelopeWith());

        assertThat(this.emitted).isEmpty();
    }

    @Test
    public void reportWritableChannelsAndListenFlags() {
        givenWritableChannel("setpoint", DataType.INTEGER);
        this.properties.put("setpoint#+listen", true);
        givenAsset();

        assertThat(this.asset.isWritableChannel("setpoint")).isTrue();
        assertThat(this.asset.isWritableChannel("unknown")).isFalse();
        assertThat(this.asset.isListeningEnabled()).isTrue();
    }

    private void givenReadChannel(final String name, final DataType type) {
        this.properties.put(name + "#+type", "READ");
        this.properties.put(name + "#+value.type", type.name());
    }

    private void givenWritableChannel(final String name, final DataType type) {
        this.properties.put(name + "#+type", "READ_WRITE");
        this.properties.put(name + "#+value.type", type.name());
    }

    private void givenAsset() {
        this.asset = new WireAsset(ASSET_PID, this.properties, this.emitted::add, new StubDriverService(this.driver));
    }

    private static WireEnvelope envelopeWith(final WireRecord... records) {
        return new WireEnvelope(ASSET_PID, List.of(records));
    }

    private static WireRecord record(final String key, final TypedValue<?> value) {
        final Map<String, TypedValue<?>> properties = new LinkedHashMap<>();
        properties.put(key, value);
        return new WireRecord(properties);
    }

    /** 只按 pid 返回固定驱动的服务桩 */
    private static final class StubDriverService implements DriverService {

        private final Driver driver;

        StubDriverService(final Driver driver) {
            this.driver = driver;
        }

        @Override
        public java.util.Collection<String> getDriverFactoryPids() {
            return List.of("test.driver");
        }

        @Override
        public boolean isDriverFactory(final String factoryPid) {
            return "test.driver".equals(factoryPid);
        }

        @Override
        public java.util.Optional<Ocd> getDefinition(final String factoryPid) {
            return java.util.Optional.empty();
        }

        @Override
        public List<com.kura.wires.model.definition.Ad> getChannelDescriptor(final String factoryPid) {
            return List.of();
        }

        @Override
        public java.util.Optional<Driver> findDriver(final String pid) {
            return DRIVER_PID.equals(pid) ? java.util.Optional.of(this.driver) : java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<String> getFactoryPid(final String pid) {
            return DRIVER_PID.equals(pid) ? java.util.Optional.of("test.driver") : java.util.Optional.empty();
        }

        @Override
        public boolean exists(final String pid) {
            return DRIVER_PID.equals(pid);
        }

        @Override
        public List<String> getDriverPids() {
            return List.of(DRIVER_PID);
        }

        @Override
        public void createDriver(final String factoryPid, final String pid, final Map<String, Object> properties) {
            throw new UnsupportedOperationException("测试桩不实现驱动创建");
        }

        @Override
        public void updateDriver(final String pid, final Map<String, Object> properties) {
            throw new UnsupportedOperationException("测试桩不实现驱动更新");
        }

        @Override
        public void deleteDriver(final String pid) {
            throw new UnsupportedOperationException("测试桩不实现驱动删除");
        }

        @Override
        public void reloadAll() {
            // 测试桩无需恢复
        }
    }

    /** 可配置的驱动桩 */
    private static final class StubDriver implements Driver {

        private final Map<String, TypedValue<?>> readable = new LinkedHashMap<>();

        private final List<ChannelRecord> written = new ArrayList<>();

        private final List<String> failingChannels = new ArrayList<>();

        private boolean failRead;

        @Override
        public void connect() {
            // 桩无需连接
        }

        @Override
        public void disconnect() {
            // 桩无需断开
        }

        @Override
        public ChannelDescriptor getChannelDescriptor() {
            return () -> List.of();
        }

        @Override
        public void read(final List<ChannelRecord> records) throws ConnectionException {
            if (this.failRead) {
                throw new ConnectionException("device unreachable");
            }
            for (final ChannelRecord record : records) {
                if (this.failingChannels.contains(record.getChannelName())) {
                    record.setChannelStatus(
                            new com.kura.wires.model.channel.ChannelStatus(ChannelFlag.FAILURE, "read failed", null));
                    record.setTimestamp(10L);
                    continue;
                }
                record.setValue(this.readable.get(record.getChannelName()));
                record.setChannelStatus(new com.kura.wires.model.channel.ChannelStatus(ChannelFlag.SUCCESS));
                record.setTimestamp(10L);
            }
        }

        @Override
        public void write(final List<ChannelRecord> records) {
            this.written.addAll(records);
            records.forEach(record -> record.setChannelStatus(
                    new com.kura.wires.model.channel.ChannelStatus(ChannelFlag.SUCCESS)));
        }

        @Override
        public void registerChannelListener(final Map<String, Object> channelConfig,
                final ChannelListener listener) {
            // 桩不产生事件
        }

        @Override
        public void unregisterChannelListener(final ChannelListener listener) {
            // 桩不产生事件
        }
    }
}
