/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelType;

/**
 * 资产的解析结果：描述、绑定的驱动 PID、通道表。
 * 对照上游 {@code org.eclipse.kura.asset.AssetConfiguration}（不可变）。
 * <p>
 * 通道表保持配置文件里的**出现顺序**（上游用 {@code Collectors.toMap} 会丢顺序，本项目用
 * {@link LinkedHashMap}——顺序只影响 UI 行展示，不影响语义）。
 * </p>
 */
public final class AssetConfiguration {

    private final String description;

    private final String driverPid;

    private final Map<String, Channel> channels;

    /**
     * 创建资产配置。
     *
     * @param description 资产描述（null 视为空串）
     * @param driverPid 驱动实例 PID（可为 null，表示尚未绑定）
     * @param channels 通道表
     */
    public AssetConfiguration(final String description, final String driverPid,
            final Map<String, Channel> channels) {
        this.description = description == null ? "" : description;
        this.driverPid = driverPid;
        final Map<String, Channel> copy = new LinkedHashMap<>();
        if (channels != null) {
            copy.putAll(channels);
        }
        this.channels = Collections.unmodifiableMap(copy);
    }

    public String getDescription() {
        return this.description;
    }

    public String getDriverPid() {
        return this.driverPid;
    }

    public Map<String, Channel> getAssetChannels() {
        return this.channels;
    }

    /**
     * 返回可读通道名（{@code READ}/{@code READ_WRITE} 且启用），顺序与配置一致。
     *
     * @return 可读通道名列表
     */
    public List<String> getReadableChannelNames() {
        return this.channels.values().stream()
                .filter(channel -> channel.isEnabled() && channel.getType() != ChannelType.WRITE)
                .map(Channel::getName).toList();
    }

    /**
     * 是否存在可读通道（决定 WireAsset 收到信封时是否需要读设备）。
     *
     * @return 有可读通道时为 true
     */
    public boolean hasReadChannels() {
        return !getReadableChannelNames().isEmpty();
    }

    @Override
    public String toString() {
        return "AssetConfiguration{driverPid=" + this.driverPid + ", channels=" + this.channels.keySet() + "}";
    }
}
