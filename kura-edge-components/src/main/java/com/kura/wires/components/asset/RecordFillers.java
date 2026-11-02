/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;

/**
 * 读记录 → 信封属性的填充规则，对照上游 {@code RecordFillers} + {@code Utils}。
 * <p>
 * 与上游的差异：记录没有状态（{@code channelStatus == null}）时按成功处理——上游这里会 NPE，
 * 而「驱动成功但没显式设状态」在第三方驱动里很常见，这里选择容错（RFC 002 §6）。
 * </p>
 */
final class RecordFillers {

    private RecordFillers() {
        // 工具类
    }

    /**
     * 把一批读记录转成信封属性表。
     *
     * @param records 读记录
     * @param options 发射选项
     * @return 信封属性（不含 {@code assetName}）
     */
    static Map<String, TypedValue<?>> toEnvelopeProperties(final List<ChannelRecord> records,
            final WireAssetOptions options) {
        final Map<String, TypedValue<?>> properties = new LinkedHashMap<>(records.size() * 2);
        final boolean perChannel = options.getTimestampMode() == TimestampMode.PER_CHANNEL;
        long maxTimestamp = 0L;
        long minTimestamp = Long.MAX_VALUE;

        for (final ChannelRecord record : records) {
            final String channelName = record.getChannelName();
            if (isSuccess(record)) {
                properties.put(channelName, record.getValue());
                final String unit = record.getUnit();
                if (unit != null && !unit.trim().isEmpty()) {
                    properties.put(channelName + WireAssetConstants.PROP_SUFFIX_UNIT,
                            TypedValues.newStringValue(unit));
                }
            }
            if (perChannel) {
                properties.put(channelName + WireAssetConstants.PROP_SUFFIX_TIMESTAMP,
                        TypedValues.newLongValue(record.getTimestamp()));
            }
            if (options.emitErrors()) {
                properties.put(channelName + WireAssetConstants.PROP_SUFFIX_ERROR,
                        TypedValues.newStringValue(errorMessage(record)));
            }
            maxTimestamp = Math.max(maxTimestamp, record.getTimestamp());
            minTimestamp = Math.min(minTimestamp, record.getTimestamp());
        }

        switch (options.getTimestampMode()) {
            case SINGLE_ASSET_GENERATED:
                properties.put(WireAssetConstants.PROP_SINGLE_TIMESTAMP_NAME,
                        TypedValues.newLongValue(System.currentTimeMillis()));
                break;
            case SINGLE_DRIVER_GENERATED_MAX:
                properties.put(WireAssetConstants.PROP_SINGLE_TIMESTAMP_NAME, TypedValues.newLongValue(maxTimestamp));
                break;
            case SINGLE_DRIVER_GENERATED_MIN:
                properties.put(WireAssetConstants.PROP_SINGLE_TIMESTAMP_NAME, TypedValues.newLongValue(minTimestamp));
                break;
            default:
                break;
        }
        return properties;
    }

    static boolean isSuccess(final ChannelRecord record) {
        final ChannelStatus status = record.getChannelStatus();
        return status == null || status.isSuccess();
    }

    private static String errorMessage(final ChannelRecord record) {
        final ChannelStatus status = record.getChannelStatus();
        if (status == null || status.isSuccess()) {
            return WireAssetConstants.PROP_VALUE_NO_ERROR;
        }
        final Exception exception = status.getException();
        final String message = status.getExceptionMessage();
        if (exception != null && message != null) {
            return message + " " + exception;
        }
        if (exception == null && message != null) {
            return message;
        }
        if (exception != null) {
            return exception.toString();
        }
        return WireAssetConstants.ERROR_NOT_SPECIFIED_MESSAGE;
    }

    /**
     * 错误信封的属性（{@code assetName} 由调用方补上）。
     *
     * @param message 错误消息
     * @param options 发射选项
     * @return 信封属性
     */
    static Map<String, TypedValue<?>> toErrorEnvelopeProperties(final String message, final WireAssetOptions options) {
        final Map<String, TypedValue<?>> properties = new LinkedHashMap<>();
        properties.put(WireAssetConstants.PROP_ASSET_ERROR,
                TypedValues.newStringValue(message == null ? "ERROR" : message));
        if (options.getTimestampMode() != TimestampMode.NO_TIMESTAMPS) {
            properties.put(WireAssetConstants.PROP_SINGLE_TIMESTAMP_NAME,
                    TypedValues.newLongValue(System.currentTimeMillis()));
        }
        return properties;
    }

    /** 读记录是否成功（供 ValueChangeCache 判定） */
    static ChannelFlag flagOf(final ChannelRecord record) {
        final ChannelStatus status = record.getChannelStatus();
        return status == null ? ChannelFlag.SUCCESS : status.getChannelFlag();
    }
}
