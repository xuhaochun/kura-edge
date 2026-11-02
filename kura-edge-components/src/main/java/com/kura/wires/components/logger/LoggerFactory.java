/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

import java.util.List;
import java.util.Map;

import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.AdOption;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import org.springframework.stereotype.Component;

/**
 * Logger 组件工厂：向引擎注册 Logger 的定义与创建逻辑，对照上游 OSGi DS +
 * metatype（{@code OSGI-INF/metatype/org.eclipse.kura.wire.Logger.xml}）。
 * <p>
 * 默认端口数与上游组件语义一致：Logger 只接收不发射（1 入 / 0 出）。
 * </p>
 */
@Component
public final class LoggerFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.Logger";

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
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public Ocd getDefinition() {
        final Ad logVerbosity = new Ad(Logger.PROP_LOG_LEVEL, "log.verbosity", Scalar.STRING, true, "QUIET");
        logVerbosity
                .setDescription("Specifies the logging verbosity to be used to log the received Wire Envelope");
        logVerbosity.setOptions(List.of(new AdOption("VERBOSE", "VERBOSE"), new AdOption("QUIET", "QUIET")));
        return new Ocd(FACTORY_PID, "Logger",
                "A wire component which logs data as received from upstream connected Wire Components",
                List.of(logVerbosity));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        return new Logger(pid, properties);
    }
}
