/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.type.DataType;
import org.junit.jupiter.api.Test;

/**
 * 资产配置解析的行为测试：键切分、通道名合法性、缺失参数静默丢弃、默认值回填。
 * <p>
 * 期望值取自上游配置样本（{@code LED#+type} 之类的键拼写），不是照抄实现里的常量拼接逻辑。
 * </p>
 */
public class AssetChannelParserTest {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private AssetConfiguration configuration;
    private Map<String, Object> withDefaults;

    @Test
    public void parseChannelWithAllBaseParameters() {
        givenProperty("LED#+enabled", true);
        givenProperty("LED#+type", "READ_WRITE");
        givenProperty("LED#+value.type", "DOUBLE");
        givenProperty("LED#+scaleoffset.type", "INTEGER");
        givenProperty("LED#+scale", "2");
        givenProperty("LED#+offset", "1");
        givenProperty("LED#+unit", "V");

        whenParsing();

        final Channel channel = this.configuration.getAssetChannels().get("LED");
        assertThat(channel).isNotNull();
        assertThat(channel.getType()).isEqualTo(ChannelType.READ_WRITE);
        assertThat(channel.getValueType()).isEqualTo(DataType.DOUBLE);
        assertThat(channel.getScaleOffsetType()).isEqualTo(ScaleOffsetType.INTEGER);
        assertThat(channel.getValueScaleAsNumber()).isEqualTo(2);
        assertThat(channel.getValueOffsetAsNumber()).isEqualTo(1);
        assertThat(channel.getUnit()).isEqualTo("V");
        assertThat(channel.isEnabled()).isTrue();
    }

    @Test
    public void defaultScaleAndOffsetWhenMissing() {
        givenMinimalChannel("ch", "READ", "INTEGER");

        whenParsing();

        final Channel channel = this.configuration.getAssetChannels().get("ch");
        assertThat(channel.getValueScale()).isEqualTo(1.0d);
        assertThat(channel.getValueOffset()).isEqualTo(0.0d);
        assertThat(channel.getScaleOffsetType()).isEqualTo(ScaleOffsetType.DEFINED_BY_VALUE_TYPE);
    }

    @Test
    public void treatMissingEnabledAsTrueAndNonBooleanValueAsFalse() {
        givenMinimalChannel("ch", "READ", "INTEGER");
        givenMinimalChannel("other", "READ", "INTEGER");
        givenProperty("other#+enabled", "not-a-boolean");

        whenParsing();

        assertThat(this.configuration.getAssetChannels().get("ch").isEnabled()).isTrue();
        assertThat(this.configuration.getAssetChannels().get("other").isEnabled()).isFalse();
    }

    @Test
    public void dropChannelWhenTypeOrValueTypeIsMissing() {
        givenMinimalChannel("ok", "READ", "INTEGER");
        givenProperty("noType#+value.type", "INTEGER");
        givenProperty("noValueType#+type", "READ");
        givenProperty("garbage#+type", "NOT_A_TYPE");

        whenParsing();

        assertThat(this.configuration.getAssetChannels()).containsOnlyKeys("ok");
    }

    @Test
    public void dropChannelWhoseNameContainsProhibitedCharacters() {
        givenProperty("bad name#+type", "READ");
        givenProperty("bad name#+value.type", "INTEGER");
        givenProperty("bad,name#+type", "READ");
        givenProperty("bad,name#+value.type", "INTEGER");
        givenProperty("ok#+type", "READ");
        givenProperty("ok#+value.type", "INTEGER");

        whenParsing();

        assertThat(this.configuration.getAssetChannels()).containsOnlyKeys("ok");
    }

    @Test
    public void ignoreKeysThatAreNotChannelParameters() {
        givenProperty(AssetConstants.ASSET_DRIVER_PROP, "driver-1");
        givenProperty(AssetConstants.ASSET_DESC_PROP, "a description");
        givenProperty("noSeparator", "x");
        givenProperty("#+type", "READ");
        givenProperty("trailing#", "x");
        givenMinimalChannel("ch", "READ", "INTEGER");

        whenParsing();

        assertThat(this.configuration.getAssetChannels()).containsOnlyKeys("ch");
        assertThat(this.configuration.getDriverPid()).isEqualTo("driver-1");
        assertThat(this.configuration.getDescription()).isEqualTo("a description");
    }

    @Test
    public void keepDriverSpecificParametersInChannelConfiguration() {
        givenMinimalChannel("ch", "READ", "INTEGER");
        givenProperty("ch#simulated.register", "reg-1");

        whenParsing();

        assertThat(this.configuration.getAssetChannels().get("ch").getConfiguration())
                .containsEntry("simulated.register", "reg-1")
                .containsEntry("+type", "READ");
    }

    @Test
    public void rejectUnparsableScaleValue() {
        givenMinimalChannel("ch", "READ", "INTEGER");
        givenProperty("ch#+scale", "abc");

        assertThatThrownBy(this::whenParsing).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be converted into a Number of type");
    }

    @Test
    public void backfillMissingRequiredChannelParametersFromDescriptor() {
        givenMinimalChannel("ch", "READ", "INTEGER");
        final Ad register = new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "register-1");
        final Ad optional = new Ad("simulated.optional", "simulated.optional", Scalar.STRING, false, "x");

        whenBackfillingDefaults(List.of(register, optional));

        assertThat(this.withDefaults)
                .containsEntry("ch#simulated.register", "register-1")
                .doesNotContainKey("ch#simulated.optional");
    }

    @Test
    public void notOverwriteExistingChannelParametersWhenBackfilling() {
        givenMinimalChannel("ch", "READ", "INTEGER");
        givenProperty("ch#simulated.register", "already-set");
        final Ad register = new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "register-1");

        whenBackfillingDefaults(List.of(register));

        assertThat(this.withDefaults).containsEntry("ch#simulated.register", "already-set");
    }

    private void givenProperty(final String key, final Object value) {
        this.properties.put(key, value);
    }

    private void givenMinimalChannel(final String name, final String type, final String valueType) {
        givenProperty(name + "#+type", type);
        givenProperty(name + "#+value.type", valueType);
    }

    private void whenParsing() {
        this.configuration = AssetChannelParser.parse(this.properties);
    }

    private void whenBackfillingDefaults(final List<Ad> ads) {
        this.withDefaults = AssetChannelParser.withChannelDefaults(this.properties, ads);
    }
}
