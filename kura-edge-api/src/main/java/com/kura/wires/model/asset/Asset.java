/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.asset;

import java.util.List;
import java.util.Set;

import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.listener.ChannelListener;

/**
 * 资产：把「一组具名通道」绑定到一个驱动上，对外提供读写与监听。
 * 对照上游 {@code org.eclipse.kura.asset.Asset}。
 * <p>
 * 语义约定（与上游一致，资产层据此被测试）：
 * </p>
 * <ul>
 * <li>{@link #readAllChannels()} 只读 {@code READ}/{@code READ_WRITE} 且启用的通道；IO 异常整体上抛。</li>
 * <li>{@link #read(Set)} 结果条数与请求条数相同；通道不存在或方向不允许时，该条是 FAILURE 状态记录，
 * 不整批失败。</li>
 * <li>{@link #write(List)} 对方向不允许的记录标记 FAILURE 后继续处理其余记录，不整批失败。</li>
 * </ul>
 */
public interface Asset {

    /**
     * 返回资产配置（描述、驱动 PID、通道表）。
     *
     * @return 资产配置
     */
    AssetConfiguration getAssetConfiguration();

    /**
     * 读取指定通道。
     *
     * @param channelNames 通道名集合
     * @return 读记录（条数与请求一致）
     */
    List<ChannelRecord> read(Set<String> channelNames);

    /**
     * 读取全部可读通道。
     *
     * @return 读记录
     */
    List<ChannelRecord> readAllChannels();

    /**
     * 注册通道监听器（本轮不实现监听行为）。
     *
     * @param channelName 通道名
     * @param channelListener 监听器
     */
    void registerChannelListener(String channelName, ChannelListener channelListener);

    /**
     * 注销通道监听器。
     *
     * @param channelListener 监听器
     */
    void unregisterChannelListener(ChannelListener channelListener);

    /**
     * 写入通道值。
     *
     * @param channelRecords 待写记录
     */
    void write(List<ChannelRecord> channelRecords);
}
