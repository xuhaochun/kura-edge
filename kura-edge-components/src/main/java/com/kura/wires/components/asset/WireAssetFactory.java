/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import java.util.Map;

import com.kura.wires.core.driver.DriverService;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.definition.Ocd;
import org.springframework.stereotype.Component;

/**
 * WireAsset 组件工厂：把「资产」注册成线图里可用的组件。
 * <p>
 * 资产既是 Drivers and Assets 页面管理的对象，也是 Wire Graph 里的一个节点——上游同样是这个模型
 * （{@code WireAsset} 实现 {@code Asset} 与 {@code WireEmitter/WireReceiver}）。实例的生命周期由
 * 资产配置掌管，因此实现的是 {@code SelfConfiguringComponent}。
 * </p>
 */
@Component
public final class WireAssetFactory implements WireComponentFactory {

    /** 工厂 PID（与上游 {@code WireAssetConstants.CONF_PID} 一致） */
    public static final String FACTORY_PID = AssetConstants.ASSET_FACTORY_PID;

    private final DriverService driverService;

    /**
     * 创建工厂。
     *
     * @param driverService 驱动服务（资产按 {@code driver.pid} 绑定驱动）
     */
    public WireAssetFactory(final DriverService driverService) {
        this.driverService = driverService;
    }

    @Override
    public String factoryPid() {
        return FACTORY_PID;
    }

    @Override
    public int defaultInputPortCount() {
        return 1;
    }

    @Override
    public int defaultOutputPortCount() {
        return 1;
    }

    @Override
    public Ocd getDefinition() {
        return WireAssetOcd.create();
    }

    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        return new WireAsset(pid, properties, emitContext, this.driverService);
    }
}
