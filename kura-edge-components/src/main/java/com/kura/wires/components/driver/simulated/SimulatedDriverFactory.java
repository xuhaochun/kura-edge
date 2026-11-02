/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.simulated;

import java.util.List;
import java.util.Map;

import com.kura.wires.engine.DriverFactory;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import org.springframework.stereotype.Component;

/**
 * 模拟驱动工厂：把 {@link SimulatedDriver} 注册进驱动服务，让 Drivers and Assets 页面有可选的驱动工厂。
 * <p>
 * 工厂 PID 是本项目新增的（上游没有对应实现，见 RFC 002 §6.3）。
 * </p>
 */
@Component
public final class SimulatedDriverFactory implements DriverFactory {

    /** 工厂 PID */
    public static final String FACTORY_PID = "org.eclipse.kura.driver.simulated";

    /** 驱动实例配置：描述（可选，纯标注用途） */
    public static final String PROP_DESCRIPTION = "simulated.description";

    private static final Ocd DEFINITION = new Ocd(FACTORY_PID, "Simulated Driver",
            "A simulated driver keeping channel values in memory (port addition, used for demos and tests)",
            List.of(descriptionAd()));

    @Override
    public String factoryPid() {
        return FACTORY_PID;
    }

    @Override
    public Ocd getDefinition() {
        return DEFINITION;
    }

    @Override
    public ChannelDescriptor getChannelDescriptor() {
        return new SimulatedChannelDescriptor();
    }

    @Override
    public Driver create(final String pid, final Map<String, Object> properties) {
        return new SimulatedDriver(pid, properties);
    }

    private static Ad descriptionAd() {
        final Ad description = new Ad(PROP_DESCRIPTION, PROP_DESCRIPTION, Scalar.STRING, false, null);
        description.setDescription("Free form description of this simulated driver instance");
        return description;
    }
}
