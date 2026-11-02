/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

/**
 * WireAsset 的信封属性名与配置常量，对照上游
 * {@code org.eclipse.kura.internal.wire.asset.WireAssetConstants}。
 * <p>
 * 信封属性名是 Wire Graph 里的兼容契约（下游组件按名字取值），禁止重命名。
 * </p>
 */
public final class WireAssetConstants {

    /** 信封属性：资产实例名 */
    public static final String PROP_ASSET_NAME = "assetName";
    /** 信封属性：资产级时间戳 */
    public static final String PROP_SINGLE_TIMESTAMP_NAME = "assetTimestamp";
    /** 信封属性：资产级错误 */
    public static final String PROP_ASSET_ERROR = "assetError";
    /** 通道属性后缀：时间戳 */
    public static final String PROP_SUFFIX_TIMESTAMP = "_timestamp";
    /** 通道属性后缀：单位 */
    public static final String PROP_SUFFIX_UNIT = "_unit";
    /** 通道属性后缀：错误消息 */
    public static final String PROP_SUFFIX_ERROR = "_error";
    /** 成功时 {@code <channel>_error} 的取值（空串） */
    public static final String PROP_VALUE_NO_ERROR = "";
    /** 没有可用错误信息时的占位文案（上游原样） */
    public static final String ERROR_NOT_SPECIFIED_MESSAGE = "ERROR NOT SPECIFIED";

    private WireAssetConstants() {
        // 常量类
    }
}
