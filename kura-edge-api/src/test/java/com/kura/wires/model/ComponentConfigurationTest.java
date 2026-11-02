package com.kura.wires.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kura.wires.model.definition.Ocd;

/**
 * 组件配置、图组件配置与整图配置的访问器与默认值行为验证。
 */
class ComponentConfigurationTest {

    @Test
    void exposeConfigurationAccessors() {
        final Ocd definition = new Ocd("id", "name", "desc", null);
        final ComponentConfiguration configuration = new ComponentConfiguration("pid", definition, Map.of("k", "v"));
        assertThat(configuration.getPid()).isEqualTo("pid");
        assertThat(configuration.getDefinition()).isSameAs(definition);
        assertThat(configuration.getProperties()).containsEntry("k", "v");

        configuration.setPid("pid2");
        configuration.setDefinition(null);
        configuration.setProperties(null);
        assertThat(configuration.getPid()).isEqualTo("pid2");
        assertThat(configuration.getDefinition()).isNull();
        assertThat(configuration.getProperties()).isEmpty();
    }

    @Test
    void useDefaultConstructorForDeserialization() {
        final ComponentConfiguration configuration = new ComponentConfiguration();
        assertThat(configuration.getPid()).isNull();
        assertThat(configuration.getDefinition()).isNull();
        assertThat(configuration.getProperties()).isEmpty();
    }

    @Test
    void exposeWireComponentConfigurationAccessors() {
        final WireComponentConfiguration component = new WireComponentConfiguration();
        assertThat(component.getConfiguration()).isNull();
        assertThat(component.getProperties()).isEmpty();

        final ComponentConfiguration configuration = new ComponentConfiguration();
        component.setConfiguration(configuration);
        component.setProperties(null);
        assertThat(component.getConfiguration()).isSameAs(configuration);
        assertThat(component.getProperties()).isEmpty();

        final WireComponentConfiguration withMap =
                new WireComponentConfiguration(configuration, Map.of("inputPortCount", 1));
        assertThat(withMap.getProperties()).containsEntry("inputPortCount", 1);
    }
    @Test
    void exposeGraphConfigurationAccessors() {
        final WireGraphConfiguration configuration = new WireGraphConfiguration();
        assertThat(configuration.getWireComponentConfigurations()).isEmpty();
        assertThat(configuration.getWireConfigurations()).isEmpty();

        configuration.setWireComponentConfigurations(
                List.of(new WireComponentConfiguration(new ComponentConfiguration(), null)));
        configuration.setWireConfigurations(List.of(new MultiportWireConfiguration("e", "r", 0, 0)));
        assertThat(configuration.getWireComponentConfigurations()).hasSize(1);
        assertThat(configuration.getWireConfigurations()).hasSize(1);
    }

    @Test
    void allowDefaultOnWireReceiveForPureEmitter() {
        final WireComponent emitter = new WireComponent() {

            @Override
            public String getPid() {
                return "emitter";
            }
        };
        // 默认实现为空操作，仅记录语义：纯发射端组件不处理信封
        emitter.onWireReceive(new WireEnvelope("emitter", List.of()));
        assertThat(emitter.getPid()).isEqualTo("emitter");
    }
}
