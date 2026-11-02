/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.core.asset.BaseAsset;
import com.kura.wires.core.driver.DriverService;
import com.kura.wires.engine.SelfConfiguringComponent;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireRecord;
import com.kura.wires.model.WireReceiver;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.type.BooleanValue;
import com.kura.wires.model.type.ByteArrayValue;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.DoubleValue;
import com.kura.wires.model.type.FloatValue;
import com.kura.wires.model.type.IntegerValue;
import com.kura.wires.model.type.LongValue;
import com.kura.wires.model.type.StringValue;
import com.kura.wires.model.type.TypedValue;
import com.kura.wires.model.type.TypedValues;

/**
 * WireAsset：既是一个 {@link com.kura.wires.model.asset.Asset}，又是一个线图组件。
 * 对照上游 {@code org.eclipse.kura.internal.wire.asset.WireAsset}。
 * <p>
 * 行为：收到信封时先「读全部可读通道并下发一个新信封」，再把信封里与通道同名且类型一致的记录写回设备。
 * 写入是「尽力而为」：单条记录的失败不会中断其它记录，也不会中断整张图（上游同样吞掉异常）。
 * </p>
 * <p>
 * 本轮不实现驱动通道事件监听（{@code +listen}），只保留配置键与 OCD 选项。
 * </p>
 */
public final class WireAsset extends BaseAsset implements WireReceiver, SelfConfiguringComponent {

    private static final Logger log = LoggerFactory.getLogger(WireAsset.class);

    private final WireEmitContext emitContext;

    private final WireAssetOptions options;

    private final ValueChangeCache valueChangeCache = new ValueChangeCache();

    /**
     * 创建 WireAsset 实例。
     *
     * @param pid 实例 PID
     * @param properties 配置属性
     * @param emitContext 引擎注入的发射上下文
     * @param driverService 驱动服务
     */
    public WireAsset(final String pid, final Map<String, Object> properties, final WireEmitContext emitContext,
            final DriverService driverService) {
        super(pid, properties, driverService);
        this.emitContext = emitContext;
        this.options = new WireAssetOptions(properties);
    }

    /**
     * 返回发射选项（测试与文档用）。
     *
     * @return 发射选项
     */
    public WireAssetOptions getOptions() {
        return this.options;
    }

    @Override
    public void onWireReceive(final WireEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        emitAllReadChannels();
        for (final WireRecord record : envelope.getRecords()) {
            writeChannels(determineWritingChannels(record));
        }
    }

    private void emitAllReadChannels() {
        if (!hasReadChannels()) {
            return;
        }
        try {
            emitChannelRecords(readAllChannels());
        } catch (final RuntimeException e) {
            log.error("Error while performing read from the Wire Asset: {}", getPid(), e);
            if (this.options.emitErrors() && this.options.emitConnectionErrors()) {
                emitAssetError(e);
            }
        }
    }

    private void emitAssetError(final RuntimeException e) {
        final Map<String, TypedValue<?>> properties = new LinkedHashMap<>();
        properties.put(WireAssetConstants.PROP_ASSET_NAME, TypedValues.newStringValue(getPid()));
        properties.putAll(RecordFillers.toErrorEnvelopeProperties(e.getMessage(), this.options));
        emit(new WireRecord(properties));
    }

    private void emitChannelRecords(final List<ChannelRecord> channelRecords) {
        if (channelRecords == null || channelRecords.isEmpty()) {
            return;
        }
        final List<ChannelRecord> toBeEmitted = this.options.emitOnChange()
                ? this.valueChangeCache.filterRecords(channelRecords)
                : channelRecords;
        if (toBeEmitted.isEmpty()) {
            return;
        }
        final Map<String, TypedValue<?>> properties = RecordFillers.toEnvelopeProperties(toBeEmitted, this.options);
        if (!this.options.emitEmptyEnvelopes() && properties.isEmpty()) {
            return;
        }
        properties.put(WireAssetConstants.PROP_ASSET_NAME, TypedValues.newStringValue(getPid()));
        emit(new WireRecord(properties));
    }

    private void emit(final WireRecord record) {
        if (this.emitContext == null) {
            log.debug("No emit context available for Wire Asset {}, dropping envelope", getPid());
            return;
        }
        this.emitContext.emit(new WireEnvelope(getPid(), List.of(record)));
    }

    private List<ChannelRecord> determineWritingChannels(final WireRecord wireRecord) {
        final List<ChannelRecord> records = new ArrayList<>();
        if (wireRecord == null || wireRecord.getProperties() == null) {
            return records;
        }
        for (final Channel channel : getAssetConfiguration().getAssetChannels().values()) {
            if (channel.getType() != ChannelType.WRITE && channel.getType() != ChannelType.READ_WRITE) {
                continue;
            }
            final TypedValue<?> value = wireRecord.getProperties().get(channel.getName());
            if (value != null && matchesType(channel.getValueType(), value)) {
                records.add(channel.createWriteRecord(value));
            }
        }
        return records;
    }

    private void writeChannels(final List<ChannelRecord> channelRecords) {
        if (channelRecords == null || channelRecords.isEmpty()) {
            return;
        }
        try {
            write(channelRecords);
        } catch (final RuntimeException e) {
            log.error("Error while performing write from the Wire Asset: {}", getPid(), e);
        }
    }

    /**
     * 信封记录的类型必须与通道 {@code +value.type} 严格一致（上游语义：不一致就静默忽略）。
     *
     * @param type 通道取值类型
     * @param value 信封里的取值
     * @return 类型一致时为 true
     */
    static boolean matchesType(final DataType type, final TypedValue<?> value) {
        switch (type) {
            case BOOLEAN:
                return value instanceof BooleanValue;
            case BYTE_ARRAY:
                return value instanceof ByteArrayValue;
            case DOUBLE:
                return value instanceof DoubleValue;
            case INTEGER:
                return value instanceof IntegerValue;
            case LONG:
                return value instanceof LongValue;
            case FLOAT:
                return value instanceof FloatValue;
            case STRING:
                return value instanceof StringValue;
            default:
                return false;
        }
    }

    /**
     * 通道名是否可写（供测试与文档表达语义）。
     *
     * @param channelName 通道名
     * @return 可写时为 true
     */
    public boolean isWritableChannel(final String channelName) {
        return findChannel(channelName).map(channel -> channel.getType() != ChannelType.READ).orElse(false);
    }

    /**
     * 返回 {@code +listen} 是否打开（本轮只读不跑，供 UI/文档核对）。
     *
     * @return 打开时为 true
     */
    public boolean isListeningEnabled() {
        return getAssetConfiguration().getAssetChannels().values().stream()
                .filter(channel -> channel.getConfiguration() != null)
                .map(channel -> channel.getConfiguration().get(AssetConstants.LISTEN))
                .anyMatch(value -> value != null && Boolean.parseBoolean(value.toString()));
    }
}
