/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.asset;

/**
 * 资产配置的键与常量，对照上游 {@code org.eclipse.kura.asset.provider.AssetConstants}
 * 与 {@code org.eclipse.kura.internal.wire.asset.WireAssetConstants}。
 * <p>
 * <b>键名是兼容契约</b>：导入 Kura 原生快照时属性名必须逐字一致，禁止重命名。
 * </p>
 */
public final class AssetConstants {

    /** 资产工厂 PID（资产即 WireAsset 组件） */
    public static final String ASSET_FACTORY_PID = "org.eclipse.kura.wire.WireAsset";

    /** 通道配置键的分隔符：{@code <channelName>#<parameter>} */
    public static final String CHANNEL_PROPERTY_SEPARATOR = "#";

    /** 与驱动无关的通道参数前缀 */
    public static final String CHANNEL_DEFAULT_PROPERTY_PREFIX = "+";

    /** 通道名禁止字符（含空格） */
    public static final String CHANNEL_NAME_PROHIBITED_CHARS = "#/+, ";

    /** 资产描述键 */
    public static final String ASSET_DESC_PROP = "asset.desc";

    /** 绑定驱动实例 PID 的键 */
    public static final String ASSET_DRIVER_PROP = "driver.pid";

    /** 通道：是否启用 */
    public static final String ENABLED = CHANNEL_DEFAULT_PROPERTY_PREFIX + "enabled";
    /** 通道：名称（展示用，实际名字取自键前缀） */
    public static final String NAME = CHANNEL_DEFAULT_PROPERTY_PREFIX + "name";
    /** 通道：读写方向 */
    public static final String TYPE = CHANNEL_DEFAULT_PROPERTY_PREFIX + "type";
    /** 通道：取值类型 */
    public static final String VALUE_TYPE = CHANNEL_DEFAULT_PROPERTY_PREFIX + "value.type";
    /** 通道：scale/offset 类型 */
    public static final String SCALE_OFFSET_TYPE = CHANNEL_DEFAULT_PROPERTY_PREFIX + "scaleoffset.type";
    /** 通道：scale */
    public static final String VALUE_SCALE = CHANNEL_DEFAULT_PROPERTY_PREFIX + "scale";
    /** 通道：offset */
    public static final String VALUE_OFFSET = CHANNEL_DEFAULT_PROPERTY_PREFIX + "offset";
    /** 通道：单位 */
    public static final String VALUE_UNIT = CHANNEL_DEFAULT_PROPERTY_PREFIX + "unit";
    /** 通道：是否监听驱动事件（本轮只存不跑） */
    public static final String LISTEN = CHANNEL_DEFAULT_PROPERTY_PREFIX + "listen";

    private AssetConstants() {
        // 常量类
    }
}
