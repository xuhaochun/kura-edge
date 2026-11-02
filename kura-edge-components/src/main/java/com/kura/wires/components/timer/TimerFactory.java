/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.timer;

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
 * Timer 组件工厂：向引擎注册 Timer 的定义与创建逻辑，对照上游 OSGi DS +
 * metatype（{@code OSGI-INF/metatype/org.eclipse.kura.wire.Timer.xml}）。
 * <p>
 * 默认端口数与上游组件语义一致：Timer 只发射不接收（0 入 / 1 出）。
 * </p>
 */
@Component
public final class TimerFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.Timer";

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
        final Ad type = new Ad(TimerOptions.PROP_INTERVAL_TYPE, "type", Scalar.STRING, true, "SIMPLE");
        type.setDescription("The type specifier for the interval");
        type.setOptions(List.of(new AdOption("SIMPLE", "SIMPLE"), new AdOption("CRON", "CRON")));

        final Ad simpleInterval = new Ad(TimerOptions.PROP_SIMPLE_INTERVAL, "simple.interval", Scalar.INTEGER, true, 10);
        simpleInterval.setDescription(
                "Interval of the timer ticks, in the time unit specified by the simple.time.unit property.");
        simpleInterval.setMin(1.0);

        final Ad simpleTimeUnit =
                new Ad(TimerOptions.PROP_SIMPLE_TIME_UNIT, "simple.time.unit", Scalar.STRING, true, "SECONDS");
        simpleTimeUnit.setDescription("The time unit to be used if SIMPLE type is selected");
        simpleTimeUnit.setOptions(List.of(new AdOption("MILLISECONDS", "MILLISECONDS"),
                new AdOption("SECONDS", "SECONDS"), new AdOption("MINUTES", "MINUTES"),
                new AdOption("HOURS", "HOURS"), new AdOption("DAYS", "DAYS")));

        final Ad firstTickPolicy = new Ad(TimerOptions.PROP_SIMPLE_TICK_POLICY, "simple.first.tick.policy",
                Scalar.STRING, true, TimerOptions.PROP_SIMPLE_TICK_POLICY_DEFAULT_VALUE);
        firstTickPolicy.setDescription("Specifies the timer starting tick behavior.");
        firstTickPolicy.setOptions(List.of(new AdOption("DEFAULT", "DEFAULT"), new AdOption("CUSTOM", "CUSTOM")));

        final Ad customFirstTickInterval = new Ad(TimerOptions.PROP_SIMPLE_TICK_CUSTOM_INTERVAL,
                "simple.custom.first.tick.interval", Scalar.INTEGER, false, 0);
        customFirstTickInterval.setDescription("Custom time before first tick (CUSTOM policy only).");

        final Ad cronInterval =
                new Ad(TimerOptions.PROP_CRON_INTERVAL, "cron.interval", Scalar.STRING, true, "0/10 * * * * ?");
        cronInterval.setDescription("Interval in CRON expression (needs to be set if CRON type is selected)");

        return new Ocd(FACTORY_PID, "Timer", "A wire component that fires a ticking event on every configured interval",
                List.of(type, simpleInterval, simpleTimeUnit, firstTickPolicy, customFirstTickInterval, cronInterval));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        final TimerOptions options = new TimerOptions(properties);
        if (options.isSimple() && options.getSimpleInterval() <= 0) {
            throw new IllegalArgumentException("simple.interval must be positive for SIMPLE timer");
        }
        return new Timer(pid, options, emitContext);
    }
}
