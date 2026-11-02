/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kura.wires.core.testsupport.RecordingDriver;
import com.kura.wires.core.testsupport.StubDriverService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.type.TypedValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 资产读写的行为测试：对照上游 {@code BaseAsset} 的读写契约
 * （只读可读通道、读结果条数恒定、方向不对的记录 FAILURE、IO 失败整体上抛、scale/offset 后处理）。
 */
public class BaseAssetTest {

    private static final String DRIVER_PID = "driver-1";

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private RecordingDriver driver;
    private StubDriverService driverService;
    private BaseAsset asset;

    @BeforeEach
    public void setUp() {
        this.driver = new RecordingDriver();
        this.driverService = new StubDriverService().withDriver(DRIVER_PID, "test.driver", this.driver);
    }

    @Test
    public void readAllChannelsSkipsWriteOnlyAndDisabledChannels() {
        givenChannel("read", "READ", "INTEGER", true);
        givenChannel("write", "WRITE", "INTEGER", true);
        givenChannel("disabled", "READ", "INTEGER", false);
        givenAsset();

        final List<ChannelRecord> records = this.asset.readAllChannels();

        assertThat(records).extracting(ChannelRecord::getChannelName).containsExactly("read");
    }

    @Test
    public void readAllChannelsReturnsDriverValues() {
        givenChannel("read", "READ", "INTEGER", true);
        this.driver.withReadable("read", TypedValues.newIntegerValue(7));
        givenAsset();

        final List<ChannelRecord> records = this.asset.readAllChannels();

        assertThat(records.get(0).getValue().getValue()).isEqualTo(7);
        assertThat(this.driver.isConnectCalled()).isTrue();
    }

    @Test
    public void applyScaleAndOffsetToReadValues() {
        givenChannel("read", "READ", "DOUBLE", true);
        this.properties.put("read#+scale", "2");
        this.properties.put("read#+offset", "0.5");
        this.driver.withReadable("read", TypedValues.newDoubleValue(3.0d));
        givenAsset();

        final List<ChannelRecord> records = this.asset.readAllChannels();

        assertThat(records.get(0).getValue().getValue()).isEqualTo(6.5d);
    }

    @Test
    public void readFailsForWholeAssetWhenDriverCannotBeReached() {
        givenChannel("read", "READ", "INTEGER", true);
        this.driver.withFailingRead();
        givenAsset();

        assertThatThrownBy(() -> this.asset.readAllChannels())
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CHANNEL_IO_FAILED);
    }

    @Test
    public void readReturnsOneRecordPerRequestedChannelWithFailuresMarked() {
        givenChannel("read", "READ", "INTEGER", true);
        givenChannel("write", "WRITE", "INTEGER", true);
        this.driver.withReadable("read", TypedValues.newIntegerValue(1));
        givenAsset();

        final List<ChannelRecord> records = this.asset.read(Set.of("read", "write", "missing"));

        assertThat(records).hasSize(3);
        assertThat(records).filteredOn(record -> "write".equals(record.getChannelName()))
                .extracting(record -> record.getChannelStatus().getExceptionMessage())
                .containsExactly("Channel type not within expected types (READ or READ_WRITE)");
        assertThat(records).filteredOn(record -> "missing".equals(record.getChannelName()))
                .extracting(record -> record.getChannelStatus().getExceptionMessage())
                .containsExactly("Channel not available");
        assertThat(records).filteredOn(record -> "read".equals(record.getChannelName()))
                .extracting(record -> record.getChannelStatus().getChannelFlag())
                .containsExactly(ChannelFlag.SUCCESS);
    }

    @Test
    public void reportDisabledChannelAsFailureOnRead() {
        givenChannel("read", "READ", "INTEGER", false);
        givenAsset();

        final List<ChannelRecord> records = this.asset.read(Set.of("read"));

        assertThat(records.get(0).getChannelStatus().getExceptionMessage()).isEqualTo("Channel is not enabled");
    }

    @Test
    public void writeSendsOnlyWritableChannelsToDriver() {
        givenChannel("readwrite", "READ_WRITE", "INTEGER", true);
        givenChannel("read", "READ", "INTEGER", true);
        givenAsset();
        final ChannelRecord writable = ChannelRecord.createWriteRecord("readwrite", com.kura.wires.model.type.DataType.INTEGER,
                TypedValues.newIntegerValue(5));
        final ChannelRecord readOnly = ChannelRecord.createWriteRecord("read", com.kura.wires.model.type.DataType.INTEGER,
                TypedValues.newIntegerValue(6));

        this.asset.write(List.of(writable, readOnly));

        assertThat(this.driver.getWritten()).extracting(ChannelRecord::getChannelName).containsExactly("readwrite");
        assertThat(readOnly.getChannelStatus().getExceptionMessage())
                .isEqualTo("Channel type not within expected types (WRITE or READ_WRITE)");
        assertThat(writable.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.SUCCESS);
    }

    @Test
    public void reportDriverWriteFailuresOnTheRecord() {
        givenChannel("readwrite", "READ_WRITE", "INTEGER", true);
        this.driver.withFailingChannel("readwrite");
        givenAsset();
        final ChannelRecord record = ChannelRecord.createWriteRecord("readwrite",
                com.kura.wires.model.type.DataType.INTEGER, TypedValues.newIntegerValue(5));

        this.asset.write(List.of(record));

        assertThat(record.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.FAILURE);
        assertThat(record.getChannelStatus().getExceptionMessage()).isEqualTo("write failed");
    }

    @Test
    public void failWithDriverNotAttachedWhenDriverPidIsMissing() {
        givenChannel("read", "READ", "INTEGER", true);
        givenAssetWithoutDriverPid();

        assertThatThrownBy(() -> this.asset.readAllChannels()).isInstanceOf(WiresException.class)
                .hasMessageContaining("Driver not attached");
    }

    @Test
    public void failWhenDriverInstanceDoesNotExist() {
        this.properties.put(AssetConstants.ASSET_DRIVER_PROP, "unknown-driver");
        givenChannel("read", "READ", "INTEGER", true);
        givenAsset();

        assertThatThrownBy(() -> this.asset.readAllChannels()).isInstanceOf(WiresException.class)
                .hasMessageContaining("Driver not attached");
    }

    @Test
    public void exposeReadableChannelFlag() {
        givenChannel("read", "READ", "INTEGER", true);
        givenAsset();

        assertThat(this.asset.hasReadChannels()).isTrue();
        assertThat(this.asset.getDriverPid()).contains(DRIVER_PID);
        assertThat(this.asset.getAssetConfiguration().getAssetChannels()).containsKey("read");
    }

    private void givenChannel(final String name, final String type, final String valueType, final boolean enabled) {
        this.properties.put(name + "#+type", type);
        this.properties.put(name + "#+value.type", valueType);
        this.properties.put(name + "#+enabled", enabled);
    }

    private void givenAssetWithoutDriverPid() {
        this.asset = new BaseAsset("asset-1", this.properties, this.driverService);
    }

    private void givenAsset() {
        if (!this.properties.containsKey(AssetConstants.ASSET_DRIVER_PROP) && this.driver != null) {
            this.properties.putIfAbsent(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID);
        }
        this.asset = new BaseAsset("asset-1", this.properties, this.driverService);
    }
}
