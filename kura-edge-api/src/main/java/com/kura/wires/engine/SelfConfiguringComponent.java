/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

/**
 * 标记接口：实例的生命周期由<b>自身配置</b>掌管，而不是由线图掌管。
 * 对照上游 {@code org.eclipse.kura.configuration.SelfConfiguringComponent}。
 * <p>
 * 典型实现是资产（{@code WireAsset}）：用户可以在 Drivers and Assets 页面里创建它、改它、删它，
 * 与它是否被某个线图引用无关。因此：
 * </p>
 * <ul>
 * <li>线图挂载时碰到已存在的实例，{@link com.kura.wires.core.registry.WireComponentRegistry#createInstance}
 * 直接复用，不另建第二个；</li>
 * <li>线图卸载/删除时只「摘线」，不销毁实例（{@code detachInstance}）。</li>
 * </ul>
 */
public interface SelfConfiguringComponent extends com.kura.wires.model.WireComponent {
    // 纯标记接口，无方法：语义即「别按图生命周期销毁我」
}
