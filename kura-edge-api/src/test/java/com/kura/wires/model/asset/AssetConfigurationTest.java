/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.type.DataType;
import org.junit.jupiter.api.Test;

/**
 * 资产配置的行为测试：可读通道筛选（方向 + 启用）与描述默认值。
 */
public class AssetConfigurationTest {

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private AssetConfiguration configuration;

    @Test
    public void listOnlyEnabledReadableChannelsInConfigurationOrder() {
        givenChannel("read", ChannelType.READ, true);
        givenChannel("write", ChannelType.WRITE, true);
        givenChannel("readwrite", ChannelType.READ_WRITE, true);
        givenChannel("disabled", ChannelType.READ, false);

        whenCreatingConfiguration();

        assertThat(this.configuration.getReadableChannelNames()).containsExactly("read", "readwrite");
        assertThat(this.configuration.hasReadChannels()).isTrue();
    }

    @Test
    public void reportNoReadableChannelsWhenAllAreWriteOnly() {
        givenChannel("write", ChannelType.WRITE, true);

        whenCreatingConfiguration();

        assertThat(this.configuration.hasReadChannels()).isFalse();
        assertThat(this.configuration.getReadableChannelNames()).isEmpty();
    }

    @Test
    public void defaultDescriptionToEmptyString() {
        whenCreatingConfigurationWithNullDescription();

        assertThat(this.configuration.getDescription()).isEmpty();
        assertThat(this.configuration.getDriverPid()).isNull();
    }

    private void givenChannel(final String name, final ChannelType type, final boolean enabled) {
        final Channel channel = new Channel(name, type, DataType.INTEGER, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 1.0d,
                0.0d, Map.of());
        channel.setEnabled(enabled);
        this.channels.put(name, channel);
    }

    private void whenCreatingConfiguration() {
        this.configuration = new AssetConfiguration("desc", "driver-1", this.channels);
    }

    private void whenCreatingConfigurationWithNullDescription() {
        this.configuration = new AssetConfiguration(null, null, this.channels);
    }
}
