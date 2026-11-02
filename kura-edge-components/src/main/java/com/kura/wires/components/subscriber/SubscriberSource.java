/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.subscriber;

import java.util.function.Consumer;

import com.kura.wires.model.WireEnvelope;

/**
 * 订阅数据源 SPI：外部数据进入 Wires 图的入口，对照上游
 * {@code org.eclipse.kura.cloudconnection.subscriber.CloudSubscriber} 服务 + 
 * {@code CloudSubscriberListener} 的绑定关系。
 * <p>
 * 上游 CloudSubscriber 在 bind 时把自己注册为云订阅器的监听器，收到云消息后
 * {@code onMessageArrived} 发射信封；本项目不接真实云平台，改为数据源持有一个
 * 「投递器」回调（即组件的 {@code CloudSubscriber#deliver(WireEnvelope)}），
 * 数据到达时调用它即可把外部数据作为信封注入图。
 * </p>
 */
public interface SubscriberSource {

    /**
     * 绑定信封投递器（对照上游 registerCloudSubscriberListener，后绑定者覆盖前者）。
     *
     * @param deliverer 数据到达时调用的投递器
     */
    void bind(Consumer<WireEnvelope> deliverer);

    /**
     * 解除绑定（对照上游 unregisterCloudSubscriberListener）。
     */
    void unbind();
}
