/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.channel;

/**
 * 通道类型，对照上游 {@code org.eclipse.kura.channel.ChannelType}。
 * <p>
 * 声明顺序与上游一致（READ、READ_WRITE、WRITE），因为 metatype 下拉选项按此顺序生成。
 * </p>
 */
public enum ChannelType {

    /** 只读 */
    READ,
    /** 读写 */
    READ_WRITE,
    /** 只写 */
    WRITE;

    /**
     * 大小写不敏感地解析通道类型（对照上游 {@code getChannelType}）。
     *
     * @param channelTypeString 字符串形式
     * @return 通道类型
     * @throws IllegalArgumentException 无法识别时
     */
    public static ChannelType getChannelType(final String channelTypeString) {
        if (channelTypeString == null) {
            throw new IllegalArgumentException("Cannot convert to ChannelType");
        }
        for (final ChannelType value : values()) {
            if (value.name().equalsIgnoreCase(channelTypeString)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Cannot convert to ChannelType");
    }
}
