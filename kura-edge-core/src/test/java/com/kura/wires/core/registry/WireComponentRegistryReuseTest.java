/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.kura.wires.engine.SelfConfiguringComponent;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ocd;
import org.junit.jupiter.api.Test;

/**
 * 自配置组件复用语义的测试：资产实例由配置掌管，线图挂载同一 pid 时必须复用而不是另建。
 */
public class WireComponentRegistryReuseTest {

    private static final String FACTORY_PID = "org.eclipse.kura.wire.WireAsset";
    private static final String PID = "asset-1";

    private WireComponentRegistry registry;
    private WireComponent first;
    private WireComponent second;

    @Test
    public void reuseSelfConfiguringInstanceForSamePid() {
        givenRegistryWith(new SelfConfiguringFactory());
        givenExistingInstance();

        this.second = this.registry.createInstance(PID, FACTORY_PID, Map.of("driver.pid", "d"));

        assertThat(this.second).isSameAs(this.first);
    }

    @Test
    public void createNewInstanceForPlainWireComponent() {
        final WireComponentRegistry plainRegistry = new WireComponentRegistry(List.of(new PlainFactory()));
        final WireComponent created = plainRegistry.createInstance(PID, FACTORY_PID, Map.of());
        final WireComponent recreated = plainRegistry.createInstance(PID, FACTORY_PID, Map.of());

        assertThat(recreated).isNotSameAs(created);
    }

    @Test
    public void destroySelfConfiguringInstanceOnRequest() {
        givenRegistryWith(new SelfConfiguringFactory());
        givenExistingInstance();

        this.registry.destroyInstance(PID);

        assertThat(this.registry.getInstance(PID)).isEmpty();
    }

    private void givenRegistryWith(final WireComponentFactory factory) {
        this.registry = new WireComponentRegistry(List.of(factory));
    }

    private void givenExistingInstance() {
        this.first = this.registry.createInstance(PID, FACTORY_PID, Map.of("driver.pid", "d"));
    }

    /** 资产那样的自配置组件 */
    private static final class SelfConfiguringComponentStub implements SelfConfiguringComponent {

        private final String pid;

        SelfConfiguringComponentStub(final String pid) {
            this.pid = pid;
        }

        @Override
        public String getPid() {
            return this.pid;
        }
    }

    /** 普通组件 */
    private static final class PlainComponentStub implements WireComponent {

        private final String pid;

        PlainComponentStub(final String pid) {
            this.pid = pid;
        }

        @Override
        public String getPid() {
            return this.pid;
        }
    }

    private static final class SelfConfiguringFactory implements WireComponentFactory {

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
            return new Ocd(FACTORY_PID, "Asset", "asset", List.of());
        }

        @Override
        public WireComponent create(final String pid, final Map<String, Object> properties,
                final WireEmitContext emitContext) {
            return new SelfConfiguringComponentStub(pid);
        }
    }

    private static final class PlainFactory implements WireComponentFactory {

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
            return new Ocd(FACTORY_PID, "Plain", "plain", List.of());
        }

        @Override
        public WireComponent create(final String pid, final Map<String, Object> properties,
                final WireEmitContext emitContext) {
            return new PlainComponentStub(pid);
        }
    }
}
