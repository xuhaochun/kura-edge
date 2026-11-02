/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.type.DataType;

/**
 * 资产配置解析器：把「扁平属性表」还原成通道表。
 * 对照上游 {@code BaseAssetConfiguration.ChannelProperty/ChannelParser}。
 * <p>
 * 关键行为（与上游一致，测试逐条锁定）：
 * </p>
 * <ul>
 * <li>键 {@code <channelName>#<parameter>} 在<b>第一个</b> {@code #} 处切分；通道名为空、或以 {@code #} 结尾的键被忽略。</li>
 * <li>通道名含 {@code "#/+, "}（含空格）任一字符 → <b>整条通道静默丢弃</b>，不报错。</li>
 * <li>缺 {@code +type} 或 {@code +value.type}（或无法解析）→ 该通道静默丢弃。</li>
 * <li>{@code +enabled} 解析失败 → 视为 {@code true}（上游同款容错）。</li>
 * <li>{@code +scale}/{@code +offset} 缺失或空串 → {@code 1.0}/{@code 0.0}；解析类型由
 * {@code +scaleoffset.type} 决定（{@code DEFINED_BY_VALUE_TYPE} 时用 {@code +value.type}）。</li>
 * </ul>
 */
public final class AssetChannelParser {

    private AssetChannelParser() {
        // 工具类
    }

    /**
     * 解析资产配置。
     *
     * @param properties 资产配置属性（含 {@code driver.pid}、{@code asset.desc} 与各通道参数）
     * @return 资产配置（通道按属性出现顺序）
     */
    public static AssetConfiguration parse(final Map<String, Object> properties) {
        final Map<String, Map<String, Object>> perChannel = new LinkedHashMap<>();
        if (properties != null) {
            for (final Map.Entry<String, Object> entry : properties.entrySet()) {
                final ChannelProperty property = ChannelProperty.parse(entry.getKey());
                if (property == null) {
                    continue;
                }
                perChannel.computeIfAbsent(property.getChannelName(), name -> new LinkedHashMap<>())
                        .put(property.getPropertyName(), entry.getValue());
            }
        }
        final Map<String, Channel> channels = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Object>> entry : perChannel.entrySet()) {
            final Channel channel = extractChannel(entry.getKey(), entry.getValue());
            if (channel != null) {
                channels.put(channel.getName(), channel);
            }
        }
        return new AssetConfiguration(asString(properties == null ? null : properties.get(AssetConstants.ASSET_DESC_PROP)),
                asString(properties == null ? null : properties.get(AssetConstants.ASSET_DRIVER_PROP)), channels);
    }

    /**
     * 为每个通道回填缺失的**必填**通道参数默认值（对照上游 {@code updateConfiguration}）。
     * <p>
     * 通道参数定义 = 基座 AD（{@code +enabled/+name/...}）在前、驱动 AD 在后；某通道缺某个必填 AD 时，
     * 用该 AD 的默认值生成 {@code <channel>#<adId>}。本方法只加键，不改已有值。
     * </p>
     *
     * @param properties 原始属性表
     * @param channelAds 合并后的通道参数定义（基座 + 驱动）；为空时原样返回
     * @return 回填后的属性表（副本）
     */
    public static Map<String, Object> withChannelDefaults(final Map<String, Object> properties,
            final List<Ad> channelAds) {
        final Map<String, Object> result = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        if (channelAds == null || channelAds.isEmpty()) {
            return result;
        }
        final Map<String, Map<String, Object>> perChannel = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : result.entrySet()) {
            final ChannelProperty property = ChannelProperty.parse(entry.getKey());
            if (property != null) {
                perChannel.computeIfAbsent(property.getChannelName(), name -> new LinkedHashMap<>())
                        .put(property.getPropertyName(), entry.getValue());
            }
        }
        for (final Map.Entry<String, Map<String, Object>> entry : perChannel.entrySet()) {
            final String channelName = entry.getKey();
            final Map<String, Object> params = entry.getValue();
            for (final Ad ad : channelAds) {
                if (ad.isRequired() && ad.getDefaultValue() != null && !params.containsKey(ad.getId())) {
                    result.put(channelName + AssetConstants.CHANNEL_PROPERTY_SEPARATOR + ad.getId(),
                            ad.getDefaultValue());
                }
            }
        }
        return result;
    }

    private static Channel extractChannel(final String channelName, final Map<String, Object> params) {
        final ChannelType type;
        try {
            type = ChannelType.getChannelType(asString(params.get(AssetConstants.TYPE)));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final DataType valueType;
        try {
            valueType = DataType.getDataType(asString(params.get(AssetConstants.VALUE_TYPE)));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final ScaleOffsetType scaleOffsetType;
        try {
            final String raw = asString(params.get(AssetConstants.SCALE_OFFSET_TYPE));
            scaleOffsetType = raw == null ? ScaleOffsetType.DEFINED_BY_VALUE_TYPE
                    : ScaleOffsetType.getScaleOffsetType(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final Channel channel = new Channel(channelName, type, valueType, scaleOffsetType,
                parseNumber(scaleOffsetType, valueType, asString(params.get(AssetConstants.VALUE_SCALE)), 1.0d),
                parseNumber(scaleOffsetType, valueType, asString(params.get(AssetConstants.VALUE_OFFSET)), 0.0d),
                params);
        channel.setEnabled(isEnabled(params));
        channel.setUnit(asString(params.get(AssetConstants.VALUE_UNIT)));
        return channel;
    }

    private static boolean isEnabled(final Map<String, Object> params) {
        // 上游是 properties.get(ENABLED).toString()：缺键时 NPE 被吞掉并回落 true，
        // 键存在但值不是 "true" 时按 Boolean.parseBoolean 得 false——这里逐条对齐
        try {
            final Object value = params.get(AssetConstants.ENABLED);
            return value == null || Boolean.parseBoolean(value.toString());
        } catch (final RuntimeException e) {
            return true;
        }
    }

    private static Number parseNumber(final ScaleOffsetType scaleOffsetType, final DataType valueType,
            final String value, final double defaultValue) {
        if (value == null || value.isEmpty()) {
            return Double.valueOf(defaultValue);
        }
        final DataType actualType = scaleOffsetType == ScaleOffsetType.DEFINED_BY_VALUE_TYPE ? valueType
                : scaleOffsetType.toDataType();
        try {
            switch (actualType) {
                case FLOAT:
                    return Float.valueOf(value);
                case DOUBLE:
                    return Double.valueOf(value);
                case INTEGER:
                    return Integer.valueOf(value);
                case LONG:
                    return Long.valueOf(value);
                default:
                    throw new IllegalArgumentException(
                            value + " cannot be converted into a Number of type " + scaleOffsetType);
            }
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(value + " cannot be converted into a Number of type " + scaleOffsetType,
                    e);
        }
    }

    private static String asString(final Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 通道属性键的解析结果：通道名 + 参数名（对照上游 {@code ChannelProperty}）。
     */
    public static final class ChannelProperty {

        private final String channelName;

        private final String propertyName;

        private ChannelProperty(final String channelName, final String propertyName) {
            this.channelName = channelName;
            this.propertyName = propertyName;
        }

        /**
         * 解析一个属性键；不是合法通道键时返回 null。
         *
         * @param propertyKey 属性键
         * @return 解析结果或 null
         */
        public static ChannelProperty parse(final String propertyKey) {
            if (propertyKey == null) {
                return null;
            }
            final int pos = propertyKey.indexOf(AssetConstants.CHANNEL_PROPERTY_SEPARATOR);
            if (pos <= 0 || pos >= propertyKey.length() - 1) {
                return null;
            }
            final String channelName = propertyKey.substring(0, pos);
            if (!isValidChannelName(channelName)) {
                return null;
            }
            return new ChannelProperty(channelName, propertyKey.substring(pos + 1));
        }

        /**
         * 通道名是否合法（不含 {@code "#/+, "} 任一字符）。
         *
         * @param channelName 通道名
         * @return 合法时为 true
         */
        public static boolean isValidChannelName(final String channelName) {
            if (channelName == null) {
                return false;
            }
            for (int i = 0; i < channelName.length(); i++) {
                if (AssetConstants.CHANNEL_NAME_PROHIBITED_CHARS.indexOf(channelName.charAt(i)) != -1) {
                    return false;
                }
            }
            return true;
        }

        public String getChannelName() {
            return this.channelName;
        }

        public String getPropertyName() {
            return this.propertyName;
        }
    }
}
