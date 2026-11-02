/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import org.junit.jupiter.api.Test;

/**
 * WireAsset 配置定义的行为测试：属性顺序、BOOLEAN 默认值的真实类型（决定 emit.* 是否生效）
 * 与 description 文案（上游兼容面）。
 */
public class WireAssetOcdTest {

    private final Ocd definition = WireAssetOcd.create();

    @Test
    public void keepUpstreamPropertyOrder() {
        assertThat(this.definition.getAds()).extracting(Ad::getId).containsExactly(
                AssetConstants.ASSET_DESC_PROP,
                AssetConstants.ASSET_DRIVER_PROP,
                WireAssetOptions.EMIT_ALL_CHANNELS_PROP_NAME,
                WireAssetOptions.TIMESTAMP_MODE_PROP_NAME,
                WireAssetOptions.EMIT_ERRORS_PROP_NAME,
                WireAssetOptions.EMIT_CONNECTION_ERRORS_PROP_NAME,
                WireAssetOptions.EMIT_ON_CHANGE_PROP_NAME,
                WireAssetOptions.EMIT_EMPTY_ENVELOPES_PROP_NAME);
    }

    @Test
    public void useRealBooleanDefaultsForBooleanProperties() {
        for (final Ad ad : this.definition.getAds()) {
            if (ad.getType() == Scalar.BOOLEAN) {
                assertThat(ad.getDefaultValue()).isInstanceOf(Boolean.class);
            }
        }
        assertThat(defaultOf(WireAssetOptions.EMIT_ALL_CHANNELS_PROP_NAME)).isEqualTo(Boolean.FALSE);
        assertThat(defaultOf(WireAssetOptions.EMIT_EMPTY_ENVELOPES_PROP_NAME)).isEqualTo(Boolean.TRUE);
    }

    @Test
    public void requireDriverPidAndOfferTimestampModes() {
        final Map<String, Ad> byId = this.definition.getAds().stream()
                .collect(java.util.stream.Collectors.toMap(Ad::getId, ad -> ad));

        assertThat(byId.get(AssetConstants.ASSET_DRIVER_PROP).isRequired()).isTrue();
        assertThat(byId.get(AssetConstants.ASSET_DESC_PROP).isRequired()).isFalse();
        assertThat(byId.get(WireAssetOptions.TIMESTAMP_MODE_PROP_NAME).getDefaultValue())
                .isEqualTo(TimestampMode.PER_CHANNEL.name());
        assertThat(byId.get(WireAssetOptions.TIMESTAMP_MODE_PROP_NAME).getOptions()).extracting("value")
                .containsExactly("NO_TIMESTAMPS", "PER_CHANNEL", "SINGLE_ASSET_GENERATED",
                        "SINGLE_DRIVER_GENERATED_MAX", "SINGLE_DRIVER_GENERATED_MIN");
        assertThat(byId.get(WireAssetOptions.EMIT_ERRORS_PROP_NAME).getDescription())
                .contains("<channel_name>_error");
    }

    @Test
    public void declareWireAssetFactoryPidAndName() {
        assertThat(this.definition.getId()).isEqualTo(AssetConstants.ASSET_FACTORY_PID);
        assertThat(this.definition.getName()).isEqualTo("Wire Asset");
        assertThat(this.definition.getAds()).hasSize(8);
    }

    private Object defaultOf(final String id) {
        return this.definition.getAds().stream().filter(ad -> id.equals(ad.getId())).findFirst().orElseThrow()
                .getDefaultValue();
    }
}
