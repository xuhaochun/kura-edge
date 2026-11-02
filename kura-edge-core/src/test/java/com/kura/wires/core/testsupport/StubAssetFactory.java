/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.testsupport;

import java.util.List;
import java.util.Map;

import com.kura.wires.engine.SelfConfiguringComponent;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.listener.ChannelListener;
import com.kura.wires.model.definition.Ocd;

/**
 * 测试用「资产组件」工厂：核心模块不该依赖 components 模块（那里才有真的 WireAsset），
 * 因此用一个最小的自配置组件桩来验证资产服务的编排行为。
 */
public final class StubAssetFactory implements WireComponentFactory {

    @Override
    public String factoryPid() {
        return AssetConstants.ASSET_FACTORY_PID;
    }

    @Override
    public int defaultInputPortCount() {
        return 1;
    }

    @Override
    public int defaultOutputPortCount() {
        return 1;
    }

    @Override
    public Ocd getDefinition() {
        return new Ocd(AssetConstants.ASSET_FACTORY_PID, "Wire Asset", "stub asset", List.of());
    }

    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        return new StubAsset(pid, properties);
    }

    /**
     * 最小资产桩：只保留配置，读写一律返回空结果。
     */
    public static final class StubAsset implements Asset, SelfConfiguringComponent {

        private final String pid;

        private final AssetConfiguration configuration;

        StubAsset(final String pid, final Map<String, Object> properties) {
            this.pid = pid;
            this.configuration = com.kura.wires.core.asset.AssetChannelParser.parse(properties);
        }

        @Override
        public String getPid() {
            return this.pid;
        }

        @Override
        public AssetConfiguration getAssetConfiguration() {
            return this.configuration;
        }

        @Override
        public List<ChannelRecord> read(final java.util.Set<String> channelNames) {
            return List.of();
        }

        @Override
        public List<ChannelRecord> readAllChannels() {
            return List.of();
        }

        @Override
        public void registerChannelListener(final String channelName, final ChannelListener channelListener) {
            // 桩不产生事件
        }

        @Override
        public void unregisterChannelListener(final ChannelListener channelListener) {
            // 桩不产生事件
        }

        @Override
        public void write(final List<ChannelRecord> channelRecords) {
            // 桩不写设备
        }
    }
}
