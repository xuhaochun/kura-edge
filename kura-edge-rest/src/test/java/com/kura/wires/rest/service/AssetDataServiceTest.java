/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.asset.AssetService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValues;
import com.kura.wires.rest.dto.AssetChannelDto;
import com.kura.wires.rest.dto.ChannelRecordDto;
import com.kura.wires.rest.dto.WriteChannelDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AssetDataService} 的行为测试：通道列表投影、读值拆包（含 base64）、
 * 写值按类型解析、未知资产 404 与非法值 400。
 */
public class AssetDataServiceTest {

    private static final String ASSET_PID = "asset-1";

    private AssetService assetService;
    private Asset asset;
    private AssetDataService service;

    @BeforeEach
    public void setUp() {
        this.assetService = mock(AssetService.class);
        this.asset = mock(Asset.class);
        this.service = new AssetDataService(this.assetService);
        when(this.assetService.exists(ASSET_PID)).thenReturn(true);
        when(this.assetService.findAsset(ASSET_PID)).thenReturn(Optional.of(this.asset));
        final Map<String, Channel> channels = Map.of("temp", new Channel("temp", ChannelType.READ, DataType.INTEGER,
                ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 1.0d, 0.0d, Map.of()));
        when(this.assetService.getAssetConfiguration(ASSET_PID))
                .thenReturn(Optional.of(new AssetConfiguration("demo", "driver-1", channels)));
    }

    @Test
    public void listChannelsFromConfiguration() {
        final List<AssetChannelDto> channels = this.service.getChannels(ASSET_PID);

        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).getName()).isEqualTo("temp");
        assertThat(channels.get(0).getValueType()).isEqualTo(DataType.INTEGER);
        assertThat(channels.get(0).isEnabled()).isTrue();
    }

    @Test
    public void readAllReturnsPlainValuesAndBase64ForBytes() {
        final ChannelRecord integer = ChannelRecord.createReadRecord("temp", DataType.INTEGER);
        integer.setValue(TypedValues.newIntegerValue(21));
        integer.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
        final ChannelRecord bytes = ChannelRecord.createReadRecord("blob", DataType.BYTE_ARRAY);
        bytes.setValue(TypedValues.newByteArrayValue(new byte[] { 0, 1, 2 }));
        bytes.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
        final ChannelRecord failed = ChannelRecord.createReadRecord("broken", DataType.INTEGER);
        failed.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, "boom", null));
        when(this.asset.readAllChannels()).thenReturn(List.of(integer, bytes, failed));

        final Map<String, Object> values = this.service.readAll(ASSET_PID);

        assertThat(values).containsEntry("temp", 21).containsEntry("blob", "AAEC").doesNotContainKey("broken");
    }

    @Test
    public void readSelectedMarksFailuresWithMessage() {
        final ChannelRecord ok = ChannelRecord.createReadRecord("temp", DataType.INTEGER);
        ok.setValue(TypedValues.newIntegerValue(5));
        ok.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
        final ChannelRecord failed = ChannelRecord.createStatusRecord("missing",
                new ChannelStatus(ChannelFlag.FAILURE, "Channel not available", null));
        when(this.asset.read(any())).thenReturn(List.of(ok, failed));

        final List<ChannelRecordDto> records = this.service.read(ASSET_PID, java.util.Set.of("temp", "missing"));

        assertThat(records).hasSize(2);
        assertThat(records.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(records.get(1).getStatus()).isEqualTo("FAILURE");
        assertThat(records.get(1).getExceptionMessage()).isEqualTo("Channel not available");
        assertThat(records.get(1).getValue()).isNull();
    }

    @Test
    public void writeParsesPlainJsonValuesByDeclaredType() {
        org.mockito.Mockito.doAnswer(invocation -> {
            final List<ChannelRecord> records = invocation.getArgument(0);
            records.forEach(record -> record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS)));
            return null;
        }).when(this.asset).write(anyList());
        final WriteChannelDto numeric = new WriteChannelDto();
        numeric.setName("temp");
        numeric.setType(DataType.INTEGER);
        numeric.setValue(7);
        final WriteChannelDto bool = new WriteChannelDto();
        bool.setName("flag");
        bool.setType(DataType.BOOLEAN);
        bool.setValue(true);

        final List<ChannelRecordDto> records = this.service.write(ASSET_PID, List.of(numeric, bool));

        assertThat(records).extracting(ChannelRecordDto::getName).containsExactly("temp", "flag");
        assertThat(records.get(0).getValue()).isEqualTo(7);
        assertThat(records.get(1).getValue()).isEqualTo(true);
    }

    @Test
    public void rejectWriteWithUnparsableValue() {
        final WriteChannelDto request = new WriteChannelDto();
        request.setName("temp");
        request.setType(DataType.INTEGER);
        request.setValue("abc");

        assertThatThrownBy(() -> this.service.write(ASSET_PID, List.of(request)))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    public void returnNotFoundForUnknownAsset() {
        assertThatThrownBy(() -> this.service.getChannels("missing"))
                .isInstanceOf(WiresException.class)
                .hasMessageContaining("Asset not found: missing");
        assertThatThrownBy(() -> this.service.readAll("missing"))
                .isInstanceOf(WiresException.class)
                .hasMessageContaining("Asset not found: missing");
    }

    @Test
    public void propagateChannelIoFailures() {
        when(this.asset.readAllChannels())
                .thenThrow(new WiresException(ErrorCode.CHANNEL_IO_FAILED, "Driver not attached"));

        assertThatThrownBy(() -> this.service.readAll(ASSET_PID)).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CHANNEL_IO_FAILED);
    }

    @Test
    public void reportMissingAssetWhenInstanceIsNotRunning() {
        when(this.assetService.findAsset(ASSET_PID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.write(ASSET_PID, List.of())).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void writeFailureFromDriverIsReportedOnRecord() {
        doThrow(new WiresException(ErrorCode.CHANNEL_IO_FAILED, "boom")).when(this.asset).write(anyList());
        final WriteChannelDto request = new WriteChannelDto();
        request.setName("temp");
        request.setType(DataType.INTEGER);
        request.setValue("1");

        assertThatThrownBy(() -> this.service.write(ASSET_PID, List.of(request)))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CHANNEL_IO_FAILED);
    }
}
