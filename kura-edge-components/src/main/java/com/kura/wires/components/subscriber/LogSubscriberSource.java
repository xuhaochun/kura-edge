/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.subscriber;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kura.wires.model.WireEnvelope;

/**
 * {@link SubscriberSource} 的日志演示实现：代替上游接入真实云平台的
 * CloudConnection 订阅器。
 * <p>
 * 以 Spring {@code @Component} 注册，供 {@link CloudSubscriberFactory} 在 create()
 * 时把组件的 {@code deliver} 绑定进来；后续接入真实数据源（MQTT 等）时
 * 提供新的 {@code SubscriberSource} 实现即可替换。
 * </p>
 */
@Component
public class LogSubscriberSource implements SubscriberSource {

    private static final Logger LOG = LoggerFactory.getLogger(LogSubscriberSource.class);

    private volatile Consumer<WireEnvelope> deliverer;

    /** {@inheritDoc} */
    @Override
    public void bind(final Consumer<WireEnvelope> deliverer) {
        this.deliverer = deliverer;
        LOG.info("Subscriber source bound to a new wire deliverer");
    }

    /** {@inheritDoc} */
    @Override
    public void unbind() {
        this.deliverer = null;
        LOG.info("Subscriber source deliverer unbound");
    }

    /**
     * 演示入口：把外部消息构造的信封转交给已绑定的投递器（未绑定时仅记录警告）。
     *
     * @param envelope 外部数据源构造的信封
     */
    public void deliver(final WireEnvelope envelope) {
        final Consumer<WireEnvelope> current = this.deliverer;
        if (current == null) {
            LOG.warn("No wire deliverer bound, dropping envelope from {}", envelope == null ? null : envelope.getEmitterPid());
            return;
        }
        current.accept(envelope);
    }
}
