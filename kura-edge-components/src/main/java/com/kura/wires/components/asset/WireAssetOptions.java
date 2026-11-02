/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import java.util.Map;

/**
 * WireAsset 的发射选项，对照上游 {@code WireAssetOptions}。
 * <p>
 * 解析语义逐条照抄上游（尤其两条「反直觉」的）：
 * </p>
 * <ul>
 * <li>{@code emit.*} 只有<b>真正的 Boolean</b> 才算 true——字符串 {@code "true"} 是 false
 * （上游 {@code prop instanceof Boolean && (Boolean) prop}）。</li>
 * <li>{@code emit.empty.envelopes} 缺省为 <b>true</b>（{@code !(prop instanceof Boolean) || (Boolean) prop}）。</li>
 * <li>{@code timestamp.mode} 解析失败回落 {@code PER_CHANNEL}。</li>
 * </ul>
 */
public final class WireAssetOptions {

    /** 配置键：通道事件时是否读全部通道 */
    public static final String EMIT_ALL_CHANNELS_PROP_NAME = "emit.all.channels";
    /** 配置键：时间戳模式 */
    public static final String TIMESTAMP_MODE_PROP_NAME = "timestamp.mode";
    /** 配置键：是否发 {@code <channel>_error} */
    public static final String EMIT_ERRORS_PROP_NAME = "emit.errors";
    /** 配置键：连接错误是否发信封 */
    public static final String EMIT_CONNECTION_ERRORS_PROP_NAME = "emit.connection.errors";
    /** 配置键：值不变是否不重发 */
    public static final String EMIT_ON_CHANGE_PROP_NAME = "emit.on.change";
    /** 配置键：空信封是否发送 */
    public static final String EMIT_EMPTY_ENVELOPES_PROP_NAME = "emit.empty.envelopes";

    private final boolean emitAllChannels;

    private final TimestampMode timestampMode;

    private final boolean emitErrors;

    private final boolean emitConnectionErrors;

    private final boolean emitOnChange;

    private final boolean emitEmptyEnvelopes;

    /**
     * 按配置属性解析选项。
     *
     * @param properties 组件配置属性
     */
    public WireAssetOptions(final Map<String, Object> properties) {
        final Map<String, Object> source = properties == null ? Map.of() : properties;
        this.emitAllChannels = isTrue(source.get(EMIT_ALL_CHANNELS_PROP_NAME));
        this.emitErrors = isTrue(source.get(EMIT_ERRORS_PROP_NAME));
        this.emitConnectionErrors = isTrue(source.get(EMIT_CONNECTION_ERRORS_PROP_NAME));
        this.emitOnChange = isTrue(source.get(EMIT_ON_CHANGE_PROP_NAME));
        final Object emitEmptyEnvelopesProp = source.get(EMIT_EMPTY_ENVELOPES_PROP_NAME);
        this.emitEmptyEnvelopes = !(emitEmptyEnvelopesProp instanceof Boolean) || (Boolean) emitEmptyEnvelopesProp;
        this.timestampMode = extractTimestampMode(source);
    }

    private static boolean isTrue(final Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static TimestampMode extractTimestampMode(final Map<String, Object> properties) {
        try {
            return TimestampMode.valueOf(properties.get(TIMESTAMP_MODE_PROP_NAME).toString());
        } catch (final RuntimeException e) {
            return TimestampMode.PER_CHANNEL;
        }
    }

    public boolean emitAllChannels() {
        return this.emitAllChannels;
    }

    public TimestampMode getTimestampMode() {
        return this.timestampMode;
    }

    public boolean emitErrors() {
        return this.emitErrors;
    }

    public boolean emitConnectionErrors() {
        return this.emitConnectionErrors;
    }

    public boolean emitOnChange() {
        return this.emitOnChange;
    }

    public boolean emitEmptyEnvelopes() {
        return this.emitEmptyEnvelopes;
    }
}
