/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.subscriber;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;

/**
 * CloudSubscriber 组件工厂：向引擎注册 CloudSubscriber 的定义与创建逻辑，对照上游
 * OSGi DS + metatype（{@code OSGI-INF/metatype/org.eclipse.kura.wire.CloudSubscriber.xml}）。
 * <p>
 * 上游在 bind 回调把组件注册为云订阅器监听器；本工厂为 Spring
 * {@code @Component}，构造注入容器中的 {@link SubscriberSource} Bean（默认
 * {@link LogSubscriberSource}），create() 时把新实例的 {@code deliver} 绑定给数据源
 * （同 PID 重建实例时后绑定者覆盖前者，演示实现不维护多实例绑定表）。
 * 默认端口数与上游一致：只发射不接收（0 入 / 1 出）。
 * </p>
 */
@Component
public class CloudSubscriberFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.CloudSubscriber";

    private final SubscriberSource source;

    /**
     * 创建工厂（Spring 注入订阅数据源）。
     *
     * @param source 容器中的订阅数据源实现
     */
    public CloudSubscriberFactory(final SubscriberSource source) {
        this.source = source;
    }

    /** {@inheritDoc} */
    @Override
    public String factoryPid() {
        return FACTORY_PID;
    }

    /** {@inheritDoc} */
    @Override
    public int defaultInputPortCount() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public int defaultOutputPortCount() {
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public Ocd getDefinition() {
        final Ad target = new Ad(CloudSubscriber.PROP_CLOUD_SUBSCRIBER_TARGET, "CloudSubscriber Target Filter",
                Scalar.STRING, true, "(kura.service.pid=changeme)");
        target.setDescription(
                "Specifies the pid of the subscriber source used to receive messages. Retained for upstream "
                        + "metatype compatibility; the source is bound via Spring injection in this project.");

        return new Ocd(FACTORY_PID, "Subscriber", "A wire component which subscribes data from the cloud platform",
                List.of(target));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        final CloudSubscriber component = new CloudSubscriber(pid, properties, emitContext);
        this.source.bind(component::deliver);
        return component;
    }
}
