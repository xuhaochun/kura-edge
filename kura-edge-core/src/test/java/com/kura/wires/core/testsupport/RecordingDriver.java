/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import com.kura.wires.model.type.TypedValue;

/**
 * 测试用驱动桩：读时按「寄存器名 → 预设值」回填，写时记录写入值并可选地让指定通道失败。
 * <p>
 * 故意贴近真实驱动行为：读记录必须有 value 与 status，写记录会被就地修改。
 * </p>
 */
public final class RecordingDriver implements Driver {

    /** 通道配置键：用于查预设值 */
    private static final String KEY = "test.key";

    private final Map<String, TypedValue<?>> readable = new LinkedHashMap<>();

    private final List<ChannelRecord> written = new ArrayList<>();

    private final List<String> failingChannels = new ArrayList<>();

    private boolean connectCalled;

    private boolean disconnectCalled;

    private boolean failRead;

    /**
     * 预设某通道读出的值。
     *
     * @param channelName 通道名
     * @param value 读出值
     * @return 本对象（链式）
     */
    public RecordingDriver withReadable(final String channelName, final TypedValue<?> value) {
        this.readable.put(channelName, value);
        return this;
    }

    /**
     * 让某通道读写时抛错（驱动内部单条失败）。
     *
     * @param channelName 通道名
     * @return 本对象（链式）
     */
    public RecordingDriver withFailingChannel(final String channelName) {
        this.failingChannels.add(channelName);
        return this;
    }

    /**
     * 让整次读操作抛连接异常。
     *
     * @return 本对象（链式）
     */
    public RecordingDriver withFailingRead() {
        this.failRead = true;
        return this;
    }

    /**
     * 返回写入过的记录。
     *
     * @return 写记录
     */
    public List<ChannelRecord> getWritten() {
        return this.written;
    }

    public boolean isConnectCalled() {
        return this.connectCalled;
    }

    public boolean isDisconnectCalled() {
        return this.disconnectCalled;
    }

    @Override
    public void connect() {
        this.connectCalled = true;
    }

    @Override
    public void disconnect() {
        this.disconnectCalled = true;
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
                record.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, "read failed", null));
                continue;
            }
            final TypedValue<?> value = this.readable.get(record.getChannelName());
            record.setValue(value);
            record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
            record.setTimestamp(1000L);
        }
    }

    @Override
    public void write(final List<ChannelRecord> records) throws ConnectionException {
        for (final ChannelRecord record : records) {
            this.written.add(record);
            if (this.failingChannels.contains(record.getChannelName())) {
                record.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, "write failed", null));
                continue;
            }
            record.setChannelStatus(new ChannelStatus(ChannelFlag.SUCCESS));
        }
    }

    @Override
    public void registerChannelListener(final Map<String, Object> channelConfig, final ChannelListener listener) {
        // 测试桩不产生通道事件
    }

    @Override
    public void unregisterChannelListener(final ChannelListener listener) {
        // 测试桩不产生通道事件
    }

    /**
     * 返回通道配置键（供夹具构造属性表）。
     *
     * @return 测试通道配置键
     */
    public static String channelParameterKey() {
        return KEY;
    }

    /**
     * 返回空的预设值（未预设时可读到的 null）。
     *
     * @param name 通道名
     * @return 预设值
     */
    public Optional<TypedValue<?>> readableValue(final String name) {
        return Optional.ofNullable(this.readable.get(name));
    }
}
