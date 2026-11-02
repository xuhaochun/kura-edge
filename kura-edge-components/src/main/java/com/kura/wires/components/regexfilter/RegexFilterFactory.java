/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.regexfilter;

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
 * RegexFilter 组件工厂：向引擎注册 RegexFilter 的定义与创建逻辑，对照上游 OSGi DS + metatype
 * （{@code OSGI-INF/metatype/org.eclipse.kura.wire.RegexFilter.xml}）。
 * <p>
 * 端口语义与上游一致：RegexFilter 同时实现 WireEmitter 与 WireReceiver，故默认 1 入 / 1 出。
 * 属性 {@code regex.filter}（STRING，默认空）与 {@code filter.type}（INTEGER，1 = RETAIN、
 * 2 = REMOVE，默认 1）的 AD id / 默认值 / 选项均照抄上游 metatype。
 * </p>
 */
@Component
public final class RegexFilterFactory implements WireComponentFactory {

    /** 工厂 PID，与上游 factoryPid 完全一致 */
    public static final String FACTORY_PID = "org.eclipse.kura.wire.RegexFilter";

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
        final Ad regex = new Ad(RegexFilter.REGEX_PROP, "regex.filter", Scalar.STRING, false, "");
        regex.setDescription(
                "Regular Expression (For more information, please visit http://regexr.com to build and test "
                        + "your own regular expression)");

        final Ad filterType = new Ad(RegexFilter.REGEX_TYPE_PROP, "filter.type", Scalar.INTEGER, false, 1);
        filterType.setDescription(
                "Filter Type (RETAIN keeps the matching entries whereas REMOVE removes the matching entries)");
        filterType.setOptions(List.of(new AdOption("RETAIN", "1"), new AdOption("REMOVE", "2")));

        return new Ocd(FACTORY_PID, "Regex Filter",
                "A Wire Component that filters the keys from incoming Wire Envelopes", List.of(regex, filterType));
    }

    /** {@inheritDoc} */
    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        // 上游对 filter.type 取值为「2 即 REMOVE，否则 RETAIN」，不做取值范围校验，此处保持一致
        return new RegexFilter(pid, properties, emitContext);
    }
}
