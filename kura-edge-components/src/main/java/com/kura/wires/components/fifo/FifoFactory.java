/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.fifo;

import java.util.List;
import java.util.Map;

import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import org.springframework.stereotype.Component;

/**
 * Fifo 组件工厂：向引擎注册 Fifo 的定义与创建逻辑，对照上游 OSGi DS + metatype
 * （{@code OSGI-INF/metatype/org.eclipse.kura.wire.Fifo.xml}）。
 * <p>
 * 默认端口数与上游组件语义一致：Fifo 既接收又发射（1 入 / 1 出）。
 * </p>
 */
@Component
public final class FifoFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.Fifo";

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
        final Ad queueCapacity = new Ad(Fifo.PROP_QUEUE_CAPACITY, "queue.capacity", Scalar.INTEGER, true, 50);
        queueCapacity.setDescription(
                "The maximum number of envelopes that can be stored in the queue of this FIFO component");
        queueCapacity.setMin(1.0);

        final Ad discardEnvelopes =
                new Ad(Fifo.PROP_DISCARD_ENVELOPES, "discard.envelopes", Scalar.BOOLEAN, true, false);
        discardEnvelopes.setDescription(
                "Defines the behavior in case of full queue: if set to true new envelopes will be dropped, "
                        + "otherwise the oldest envelope is emitted to make room before enqueueing.");

        return new Ocd(FACTORY_PID, "Fifo",
                "A wire component that adds the received envelopes to a FIFO queue, and delivers them "
                        + "using a dedicated thread.",
                List.of(queueCapacity, discardEnvelopes));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        final Object capacity = properties.get(Fifo.PROP_QUEUE_CAPACITY);
        if (capacity instanceof Integer && (Integer) capacity <= 0) {
            throw new IllegalArgumentException("queue.capacity must be positive");
        }
        return new Fifo(pid, properties, emitContext);
    }
}
