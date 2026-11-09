/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.core.driver.DriverService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.ChannelStatus;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.driver.Driver;

/**
 * 资产基类：把通道配置表变成真正的设备读写。
 * 对照上游 {@code org.eclipse.kura.asset.provider.BaseAsset}（去掉 OSGi 生命周期，保留语义）。
 * <p>
 * 与上游的差异（均已写进 RFC 002）：
 * </p>
 * <ul>
 * <li>驱动按 {@code driver.pid} 从 {@link DriverService} 查找，首次读写时惰性 {@code connect()}；
 * 没有 {@code PreparedRead} 复用。</li>
 * <li>通道监听器（{@code +listen}）本轮不实现，注册/注销是空实现。</li>
 * </ul>
 */
public class BaseAsset implements Asset {

    private static final Logger log = LoggerFactory.getLogger(BaseAsset.class);

    /** 驱动未绑定时的错误消息（与上游逐字一致） */
    protected static final String DRIVER_NOT_ATTACHED = "Driver not attached";

    private final String pid;

    private final AssetConfiguration configuration;

    private final DriverService driverService;

    private final Map<String, Channel> channels;

    private Driver driver;

    private boolean connected;

    /**
     * 按配置属性创建资产（解析在构造时完成，同上游 {@code activate}）。
     *
     * @param pid 资产实例 PID
     * @param properties 资产配置属性
     * @param driverService 驱动服务（按 {@code driver.pid} 查找驱动）
     */
    public BaseAsset(final String pid, final Map<String, Object> properties, final DriverService driverService) {
        this.pid = pid;
        this.driverService = driverService;
        this.configuration = AssetChannelParser.parse(properties);
        this.channels = this.configuration.getAssetChannels();
    }

    @Override
    public AssetConfiguration getAssetConfiguration() {
        return this.configuration;
    }

    /**
     * 返回资产实例 PID（WireAsset 用作信封里的 {@code assetName}）。
     *
     * @return 实例 PID
     */
    public String getPid() {
        return this.pid;
    }

    @Override
    public List<ChannelRecord> readAllChannels() {
        final Driver attached = requireDriver();
        final List<ChannelRecord> records = getAllReadRecords();
        if (!records.isEmpty()) {
            try {
                attached.read(records);
            } catch (final WiresException e) {
                throw e;
            } catch (final Driver.ConnectionException | RuntimeException e) {
                this.connected = false;
                throw new WiresException(ErrorCode.CHANNEL_IO_FAILED,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
            }
        }
        return ScaleOffsetApplier.apply(records, this.channels);
    }

    @Override
    public List<ChannelRecord> read(final Set<String> channelNames) {
        final Driver attached = requireDriver();
        final List<ChannelRecord> channelRecords = new ArrayList<>(channelNames.size());
        final List<ChannelRecord> validRecords = new ArrayList<>(channelNames.size());
        for (final String name : channelNames) {
            final Channel channel = this.channels.get(name);
            try {
                validateChannel(channel, EnumSet.of(ChannelType.READ, ChannelType.READ_WRITE),
                        "Channel type not within expected types (READ or READ_WRITE)");
            } catch (final IllegalArgumentException e) {
                final ChannelRecord failed = ChannelRecord.createStatusRecord(name,
                        new ChannelStatus(ChannelFlag.FAILURE, e.getMessage(), e));
                failed.setTimestamp(System.currentTimeMillis());
                channelRecords.add(failed);
                continue;
            }
            final ChannelRecord record = channel.createReadRecord();
            validRecords.add(record);
            channelRecords.add(record);
        }
        if (!validRecords.isEmpty()) {
            try {
                attached.read(validRecords);
            } catch (final WiresException e) {
                throw e;
            } catch (final Driver.ConnectionException | RuntimeException e) {
                this.connected = false;
                throw new WiresException(ErrorCode.CHANNEL_IO_FAILED,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
            }
        }
        return ScaleOffsetApplier.apply(channelRecords, this.channels);
    }

    @Override
    public void write(final List<ChannelRecord> channelRecords) {
        final Driver attached = requireDriver();
        final List<ChannelRecord> validRecords = new ArrayList<>(channelRecords.size());
        for (final ChannelRecord record : channelRecords) {
            final Channel channel = this.channels.get(record.getChannelName());
            try {
                validateChannel(channel, EnumSet.of(ChannelType.WRITE, ChannelType.READ_WRITE),
                        "Channel type not within expected types (WRITE or READ_WRITE)");
            } catch (final IllegalArgumentException e) {
                record.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, e.getMessage(), e));
                record.setTimestamp(System.currentTimeMillis());
                continue;
            }
            record.setChannelConfig(channel.getConfiguration());
            validRecords.add(record);
        }
        if (!validRecords.isEmpty()) {
            try {
                attached.write(validRecords);
            } catch (final WiresException e) {
                throw e;
            } catch (final Driver.ConnectionException | RuntimeException e) {
                this.connected = false;
                throw new WiresException(ErrorCode.CHANNEL_IO_FAILED,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
            }
        }
    }

    @Override
    public void registerChannelListener(final String channelName, final ChannelListener channelListener) {
        log.debug("Channel listeners for {} are not supported yet (asset {})", channelName, this.pid);
    }

    @Override
    public void unregisterChannelListener(final ChannelListener channelListener) {
        log.debug("Channel listeners are not supported yet (asset {})", this.pid);
    }

    /**
     * 返回全部「待读」记录（启用的 READ/READ_WRITE 通道），对照上游 {@code getAllReadRecords}。
     *
     * @return 读记录
     */
    public List<ChannelRecord> getAllReadRecords() {
        final List<ChannelRecord> records = new ArrayList<>();
        for (final Channel channel : this.channels.values()) {
            if (channel.isEnabled() && channel.getType() != ChannelType.WRITE) {
                records.add(channel.createReadRecord());
            }
        }
        return records;
    }

    /**
     * 是否存在可读通道（决定收到信封时是否要读设备）。
     *
     * @return 有可读通道时为 true
     */
    public boolean hasReadChannels() {
        return this.configuration.hasReadChannels();
    }

    /**
     * 返回绑定的驱动 PID。
     *
     * @return 驱动 PID（未配置时为空）
     */
    public Optional<String> getDriverPid() {
        return Optional.ofNullable(this.configuration.getDriverPid());
    }

    /**
     * 按通道名查通道（{@code WireAsset} 判定写记录时用）。
     *
     * @param channelName 通道名
     * @return 通道
     */
    protected Optional<Channel> findChannel(final String channelName) {
        return Optional.ofNullable(this.channels.get(channelName));
    }

    private Driver requireDriver() {
        final String driverPid = this.configuration.getDriverPid();
        if (driverPid == null) {
            throw new WiresException(ErrorCode.CHANNEL_IO_FAILED, DRIVER_NOT_ATTACHED);
        }
        final Driver resolved = this.driverService.findDriver(driverPid)
                .orElseThrow(() -> new WiresException(ErrorCode.CHANNEL_IO_FAILED, DRIVER_NOT_ATTACHED));
        if (resolved != this.driver) {
            this.driver = resolved;
            this.connected = false;
        }
        if (!this.connected) {
            try {
                resolved.connect();
                this.connected = true;
            } catch (final Driver.ConnectionException | RuntimeException e) {
                throw new WiresException(ErrorCode.CHANNEL_IO_FAILED,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
            }
        }
        return resolved;
    }

    private static void validateChannel(final Channel channel, final EnumSet<ChannelType> allowedTypes,
            final String typeNotAllowedMessage) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel not available");
        } else if (!allowedTypes.contains(channel.getType())) {
            throw new IllegalArgumentException(typeNotAllowedMessage);
        } else if (!channel.isEnabled()) {
            throw new IllegalArgumentException("Channel is not enabled");
        }
    }
}
