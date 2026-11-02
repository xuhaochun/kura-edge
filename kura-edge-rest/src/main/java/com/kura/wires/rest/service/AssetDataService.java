/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kura.wires.core.asset.AssetService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;
import com.kura.wires.rest.dto.AssetChannelDto;
import com.kura.wires.rest.dto.ChannelRecordDto;
import com.kura.wires.rest.dto.WriteChannelDto;
import org.springframework.stereotype.Service;

/**
 * 资产运行期数据服务：通道列表、读值、写值。
 * <p>
 * 对照上游 {@code AssetRestService}（{@code /services/assets/{pid}/_read|_write}）与 GWT
 * {@code GwtDriverAndAssetServiceImpl.readAllChannels/write} 的取值转换规则：
 * 读结果把 {@link TypedValue} 拆成原始 JSON 值（字节数组 base64），写请求把 JSON 值按 {@link DataType}
 * 解析回 {@link TypedValue}（字节数组 base64 解码）。
 * </p>
 */
@Service
public class AssetDataService {

    private final AssetService assetService;

    /**
     * 创建服务。
     *
     * @param assetService 资产服务
     */
    public AssetDataService(final AssetService assetService) {
        this.assetService = assetService;
    }

    /**
     * 返回资产的通道列表（配置层面，不读设备）。
     *
     * @param pid 资产实例 PID
     * @return 通道列表（配置顺序）
     */
    public List<AssetChannelDto> getChannels(final String pid) {
        final AssetConfiguration configuration = requireConfiguration(pid);
        final List<AssetChannelDto> result = new ArrayList<>();
        for (final Channel channel : configuration.getAssetChannels().values()) {
            result.add(new AssetChannelDto(channel.getName(), channel.getType(), channel.getValueType(),
                    channel.getScaleOffsetType(), channel.getValueScale(), channel.getValueOffset(), channel.getUnit(),
                    channel.isEnabled()));
        }
        return result;
    }

    /**
     * 读全部可读通道，返回「通道名 → 原始值」（只含成功值），对照上游 {@code GET /assets/{pid}/_read}。
     *
     * @param pid 资产实例 PID
     * @return 通道取值映射（跳过的通道表示读取失败）
     */
    public Map<String, Object> readAll(final String pid) {
        final List<ChannelRecord> records = requireAsset(pid).readAllChannels();
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final ChannelRecord record : records) {
            if (isSuccess(record)) {
                values.put(record.getChannelName(), unwrap(record.getValue()));
            }
        }
        return values;
    }

    /**
     * 读取指定通道，返回带状态的记录（失败的通道以 FAILURE + 原因返回，不整批失败）。
     *
     * @param pid 资产实例 PID
     * @param channelNames 通道名集合
     * @return 记录列表
     */
    public List<ChannelRecordDto> read(final String pid, final Set<String> channelNames) {
        return toDtos(requireAsset(pid).read(channelNames));
    }

    /**
     * 写入通道值，返回写后记录（每条带状态与失败原因）。
     *
     * @param pid 资产实例 PID
     * @param requests 写请求
     * @return 记录列表
     */
    public List<ChannelRecordDto> write(final String pid, final List<WriteChannelDto> requests) {
        final Asset asset = requireAsset(pid);
        final List<ChannelRecord> records = new ArrayList<>();
        for (final WriteChannelDto request : requests) {
            final TypedValue<?> value;
            try {
                value = TypedValues.parseTypedValue(request.getType(), plainString(request.getValue()));
            } catch (final IllegalArgumentException e) {
                throw new WiresException(ErrorCode.VALIDATION_FAILED, "\"" + request.getName() + "\": "
                        + e.getMessage(), e);
            }
            records.add(ChannelRecord.createWriteRecord(request.getName(), request.getType(), value));
        }
        asset.write(records);
        return toDtos(records);
    }

    private Asset requireAsset(final String pid) {
        if (!this.assetService.exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND, "Asset not found: " + pid);
        }
        return this.assetService.findAsset(pid)
                .orElseThrow(() -> new WiresException(ErrorCode.NOT_FOUND, "Asset not found: " + pid));
    }

    private AssetConfiguration requireConfiguration(final String pid) {
        if (!this.assetService.exists(pid)) {
            throw new WiresException(ErrorCode.NOT_FOUND, "Asset not found: " + pid);
        }
        return this.assetService.getAssetConfiguration(pid)
                .orElseThrow(() -> new WiresException(ErrorCode.NOT_FOUND, "Asset not found: " + pid));
    }

    private static List<ChannelRecordDto> toDtos(final List<ChannelRecord> records) {
        final List<ChannelRecordDto> result = new ArrayList<>(records.size());
        for (final ChannelRecord record : records) {
            final ChannelStatus status = record.getChannelStatus();
            result.add(new ChannelRecordDto(record.getChannelName(), record.getValueType(),
                    isSuccess(record) ? unwrap(record.getValue()) : null,
                    status == null ? ChannelFlag.SUCCESS.name() : status.getChannelFlag().name(),
                    status == null ? null : status.getExceptionMessage()));
        }
        return result;
    }

    private static boolean isSuccess(final ChannelRecord record) {
        return record.getChannelStatus() == null || record.getChannelStatus().isSuccess();
    }

    private static Object unwrap(final TypedValue<?> value) {
        if (value == null) {
            return null;
        }
        final Object raw = value.getValue();
        if (raw instanceof final byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return raw;
    }

    private static String plainString(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof final byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }
}
