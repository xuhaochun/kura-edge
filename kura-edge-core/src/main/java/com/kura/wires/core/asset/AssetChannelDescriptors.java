/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import java.util.ArrayList;
import java.util.List;

import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.AdOption;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.type.DataType;

/**
 * 基座通道描述符：与驱动无关的通道参数（{@code +enabled/+name/+type/+value.type/
 * +scaleoffset.type/+scale/+offset/+unit/+listen}）。
 * 对照上游 {@code BaseChannelDescriptor} + {@code WireAssetChannelDescriptor}。
 * <p>
 * 顺序与上游一致：基座 8 个参数在前，{@code WireAsset} 追加的 {@code +listen} 在最后。
 * 这个顺序是 UI 列表头顺序，也是「前导 {@code +} 段 = 基座」判据的依据。
 * </p>
 */
public final class AssetChannelDescriptors {

    private static final List<Ad> BASE = build();

    private AssetChannelDescriptors() {
        // 工具类
    }

    /**
     * 返回基座通道参数定义（不可变）。
     *
     * @return 基座 AD 列表
     */
    public static List<Ad> base() {
        return BASE;
    }

    /**
     * 把基座描述符与驱动描述符合并（基座在前，驱动在后），对照上游
     * {@code BaseAssetConfiguration.buildDefinition} 的拼接顺序。
     *
     * @param driverAds 驱动通道参数定义（可为 null）
     * @return 合并后的 AD 列表
     */
    public static List<Ad> merge(final List<Ad> driverAds) {
        final List<Ad> merged = new ArrayList<>(BASE);
        if (driverAds != null) {
            merged.addAll(driverAds);
        }
        return merged;
    }

    /**
     * 判断某 AD 是否属于基座（id 以 {@code +} 开头）。
     *
     * @param ad 通道参数定义
     * @return 基座参数时为 true
     */
    public static boolean isBaseParameter(final Ad ad) {
        return ad != null && ad.getId() != null && ad.getId().startsWith(AssetConstants.CHANNEL_DEFAULT_PROPERTY_PREFIX);
    }

    private static List<Ad> build() {
        final List<Ad> ads = new ArrayList<>();

        final Ad enabled = new Ad(AssetConstants.ENABLED, "enabled", Scalar.BOOLEAN, true, true);
        enabled.setDescription("Determines if the channel is enabled or not");
        ads.add(enabled);

        final Ad name = new Ad(AssetConstants.NAME, "name", Scalar.STRING, true, "Channel-1");
        name.setDescription("Name of the Channel");
        ads.add(name);

        final Ad type = new Ad(AssetConstants.TYPE, "type", Scalar.STRING, true, ChannelType.READ.name());
        type.setDescription("Type of the channel");
        type.setOptions(options(ChannelType.values()));
        ads.add(type);

        final Ad valueType = new Ad(AssetConstants.VALUE_TYPE, "value.type", Scalar.STRING, true,
                DataType.INTEGER.name());
        valueType.setDescription("Value type of the channel");
        valueType.setOptions(options(DataType.values()));
        ads.add(valueType);

        final Ad scaleOffsetType = new Ad(AssetConstants.SCALE_OFFSET_TYPE, "scaleoffset.type", Scalar.STRING, false,
                ScaleOffsetType.DEFINED_BY_VALUE_TYPE.name());
        scaleOffsetType.setDescription("Scale/Offset type of the channel");
        scaleOffsetType.setOptions(options(ScaleOffsetType.values()));
        ads.add(scaleOffsetType);

        final Ad scale = new Ad(AssetConstants.VALUE_SCALE, "scale", Scalar.STRING, false, null);
        scale.setDescription("Scale to be applied to the numeric value of the channel.");
        ads.add(scale);

        final Ad offset = new Ad(AssetConstants.VALUE_OFFSET, "offset", Scalar.STRING, false, null);
        offset.setDescription("Offset to be applied to the numeric value of the channel");
        ads.add(offset);

        final Ad unit = new Ad(AssetConstants.VALUE_UNIT, "unit", Scalar.STRING, false, "");
        unit.setDescription("Unit associated to the value of the channel");
        ads.add(unit);

        final Ad listen = new Ad(AssetConstants.LISTEN, "listen", Scalar.BOOLEAN, true, false);
        listen.setDescription("Specifies if WireAsset should emit envelopes on Channel events");
        ads.add(listen);

        return List.copyOf(ads);
    }

    private static List<AdOption> options(final Enum<?>[] values) {
        final List<AdOption> options = new ArrayList<>();
        for (final Enum<?> value : values) {
            options.add(new AdOption(value.name(), value.name()));
        }
        return options;
    }
}
