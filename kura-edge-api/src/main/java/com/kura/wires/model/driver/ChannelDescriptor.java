/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.driver;

/**
 * 通道描述符：驱动把「自己支持的通道参数」以 {@code AD} 列表的形式暴露出来。
 * 对照上游 {@code org.eclipse.kura.driver.ChannelDescriptor}。
 * <p>
 * 返回体的实际类型约定为 {@code java.util.List<com.kura.wires.model.definition.Ad>}——上游是
 * {@code List<Tad>}（OSGi metatype），本项目改为自己的 {@code Ad}，语义不变：每个 AD 描述一个
 * 通道参数（id 形如 {@code simulated.register}，<b>不带</b> {@code +} 前缀）。
 * </p>
 */
public interface ChannelDescriptor {

    /**
     * 返回通道参数定义列表（{@code List<Ad>}）。
     *
     * @return 通道参数定义
     */
    Object getDescriptor();
}
