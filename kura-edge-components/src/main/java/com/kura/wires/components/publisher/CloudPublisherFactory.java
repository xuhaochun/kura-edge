/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.publisher;

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
 * CloudPublisher 组件工厂：向引擎注册 CloudPublisher 的定义与创建逻辑，对照上游
 * OSGi DS + metatype（{@code OSGI-INF/metatype/org.eclipse.kura.wire.CloudPublisher.xml}）。
 * <p>
 * 上游通过 OSGi target filter 绑定云发布器服务；本工厂为 Spring
 * {@code @Component}，构造注入容器中的 {@link PublisherSink} Bean（默认
 * {@link LogPublisherSink}）并在 create() 时传给组件实例。默认端口数 1 入 / 1 出
 * （支持 {@code publish.forward} 向下游转发，见 {@link CloudPublisher} 说明）。
 * </p>
 */
@Component
public class CloudPublisherFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.CloudPublisher";

    private final PublisherSink sink;

    /**
     * 创建工厂（Spring 注入发布水槽）。
     *
     * @param sink 容器中的发布水槽实现
     */
    public CloudPublisherFactory(final PublisherSink sink) {
        this.sink = sink;
    }

    /** {@inheritDoc} */
    @Override
    public String factoryPid() {
        return FACTORY_PID;
    }

    /** {@inheritDoc} */
    @Override
    public int defaultInputPortCount() {
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public int defaultOutputPortCount() {
        return 1;
    }

    /** {@inheritDoc} */
    @Override
    public Ocd getDefinition() {
        final Ad target = new Ad(CloudPublisher.PROP_CLOUD_PUBLISHER_TARGET, "CloudPublisher Target Filter",
                Scalar.STRING, true, "(kura.service.pid=changeme)");
        target.setDescription(
                "Specifies the pid of the publisher sink used to publish messages. Retained for upstream "
                        + "metatype compatibility; the sink is bound via Spring injection in this project.");

        final Ad forward = new Ad(CloudPublisher.PROP_PUBLISH_FORWARD, "publish.forward", Scalar.BOOLEAN, true,
                false);
        forward.setDescription(
                "Set true to keep emitting the received envelope to downstream wire components after publishing.");

        return new Ocd(FACTORY_PID, "Publisher", "A wire component which publishes data to the cloud platform",
                List.of(target, forward));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        return new CloudPublisher(pid, this.sink, properties, emitContext);
    }
}
