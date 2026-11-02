/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

/**
 * WireAsset 的时间戳模式，对照上游 {@code TimestampMode}。
 */
public enum TimestampMode {

    /** 不发任何时间戳 */
    NO_TIMESTAMPS,
    /** 每个通道一条 {@code <channel>_timestamp} */
    PER_CHANNEL,
    /** 一个 {@code assetTimestamp} = 当前时间 */
    SINGLE_ASSET_GENERATED,
    /** 一个 {@code assetTimestamp} = 驱动时间戳的最大值 */
    SINGLE_DRIVER_GENERATED_MAX,
    /** 一个 {@code assetTimestamp} = 驱动时间戳的最小值 */
    SINGLE_DRIVER_GENERATED_MIN
}
