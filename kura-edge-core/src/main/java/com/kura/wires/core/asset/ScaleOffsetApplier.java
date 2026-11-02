/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import java.util.List;
import java.util.Map;

import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;

/**
 * 读结果的后处理：对数值型通道应用 scale/offset。
 * 对照上游 {@code BaseAsset.getFinalRecords/shouldApplyScaleAndOffset/applyScaleAndOffset}。
 * <p>
 * 运算类型的选择与上游一致：{@code +scaleoffset.type} 为 {@code DEFINED_BY_VALUE_TYPE} 时按通道
 * {@code +value.type} 运算；显式指定时按该类型运算，再把结果转换回通道 {@code +value.type}。
 * 只有 {@code DOUBLE/FLOAT/INTEGER/LONG} 参与运算，{@code BOOLEAN/STRING/BYTE_ARRAY} 原样返回。
 * </p>
 */
public final class ScaleOffsetApplier {

    private ScaleOffsetApplier() {
        // 工具类
    }

    /**
     * 就地对读记录应用 scale/offset（scale=1 且 offset=0 时跳过）。
     * <p>
     * 与上游的差异：通道为 null 或记录值为 null 时跳过（上游会 NPE）——线上不可达，但测试可构造，
     * 这里选择不抛（RFC 002 §12.4 的显式决定）。
     * </p>
     *
     * @param records 读记录
     * @param channels 通道表（按通道名索引）
     * @return 同一个列表（就地修改）
     */
    public static List<ChannelRecord> apply(final List<ChannelRecord> records, final Map<String, Channel> channels) {
        if (records == null || channels == null) {
            return records;
        }
        for (final ChannelRecord record : records) {
            final Channel channel = channels.get(record.getChannelName());
            if (!shouldApplyScaleAndOffset(record, channel)) {
                continue;
            }
            record.setValue(scaleAndOffset(record.getValue(), channel));
        }
        return records;
    }

    /**
     * 是否需要对这条记录做换算。
     *
     * @param record 记录
     * @param channel 通道
     * @return 需要换算时为 true
     */
    public static boolean shouldApplyScaleAndOffset(final ChannelRecord record, final Channel channel) {
        return record != null && record.getValue() != null && channel != null
                && (!Double.valueOf(1.0d).equals(channel.getValueScaleAsNumber().doubleValue())
                        || !Double.valueOf(0.0d).equals(channel.getValueOffsetAsNumber().doubleValue()));
    }

    private static TypedValue<?> scaleAndOffset(final TypedValue<?> value, final Channel channel) {
        if (!(value.getValue() instanceof final Number number)) {
            return value;
        }
        final DataType arithmeticType = channel.getScaleOffsetType() == ScaleOffsetType.DEFINED_BY_VALUE_TYPE
                ? channel.getValueType()
                : channel.getScaleOffsetType().toDataType();
        final double scale = channel.getValueScaleAsNumber().doubleValue();
        final double offset = channel.getValueOffsetAsNumber().doubleValue();
        final Number scaled;
        switch (arithmeticType) {
            case FLOAT:
                scaled = Float.valueOf((float) (number.floatValue() * (float) scale + (float) offset));
                break;
            case DOUBLE:
                scaled = Double.valueOf(number.doubleValue() * scale + offset);
                break;
            case INTEGER:
                scaled = Integer.valueOf((int) (number.intValue() * (int) scale + (int) offset));
                break;
            case LONG:
                scaled = Long.valueOf(number.longValue() * (long) scale + (long) offset);
                break;
            default:
                throw new IllegalStateException("Unsupported channel type: " + channel.getValueType());
        }
        return toTypedValue(channel.getValueType(), scaled);
    }

    private static TypedValue<?> toTypedValue(final DataType type, final Number value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot convert to TypedValue: value cannot be null");
        }
        switch (type) {
            case DOUBLE:
                return TypedValues.newDoubleValue(value.doubleValue());
            case FLOAT:
                return TypedValues.newFloatValue(value.floatValue());
            case INTEGER:
                return TypedValues.newIntegerValue(value.intValue());
            case LONG:
                return TypedValues.newLongValue(value.longValue());
            default:
                throw new IllegalArgumentException(
                        value + " cannot be converted into a TypedValue of type " + type);
        }
    }
}
