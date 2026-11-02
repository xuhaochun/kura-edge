package com.kura.wires.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 连线与整图配置的 equals / 不可变语义验证。
 */
class WireConfigurationTest {

    @Test
    void compareWiresByPidsOnly() {
        final WireConfiguration a = new WireConfiguration("e", "r");
        final WireConfiguration b = new WireConfiguration("e", "r");
        final WireConfiguration c = new WireConfiguration("e", "r2");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(new MultiportWireConfiguration("e", "r", 0, 0));
    }

    @Test
    void compareMultiportWiresByPortsToo() {
        final MultiportWireConfiguration a = new MultiportWireConfiguration("e", "r", 0, 1);
        final MultiportWireConfiguration b = new MultiportWireConfiguration("e", "r", 0, 1);
        final MultiportWireConfiguration c = new MultiportWireConfiguration("e", "r", 1, 0);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);

        a.setEmitterPort(1);
        a.setReceiverPort(0);
        assertThat(a.getEmitterPort()).isEqualTo(1);
        assertThat(a.getReceiverPort()).isEqualTo(0);
        assertThat(a).isEqualTo(c).hasSameHashCodeAs(c);
    }

    @Test
    void keepGraphConfigurationListsUnmodifiable() {
        final WireGraphConfiguration config = new WireGraphConfiguration(
                List.of(new WireComponentConfiguration()),
                List.of(new MultiportWireConfiguration("e", "r", 0, 0)));
        assertThat(config.getWireComponentConfigurations()).hasSize(1);
        assertThat(config.getWireConfigurations()).hasSize(1);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> config.getWireConfigurations().add(new MultiportWireConfiguration()));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> config.getWireComponentConfigurations().add(new WireComponentConfiguration()));
    }

    @Test
    void tolerateNullListsInGraphConfiguration() {
        final WireGraphConfiguration config = new WireGraphConfiguration(null, null);
        assertThat(config.getWireComponentConfigurations()).isEmpty();
        assertThat(config.getWireConfigurations()).isEmpty();
        config.setWireComponentConfigurations(null);
        config.setWireConfigurations(null);
        assertThat(config.getWireComponentConfigurations()).isEmpty();
        assertThat(config.getWireConfigurations()).isEmpty();
    }

    @Test
    void keepComponentConfigurationPropertiesUnmodifiable() {
        final WireComponentConfiguration wcc = new WireComponentConfiguration();
        wcc.setConfiguration(null);
        wcc.setProperties(null);
        assertThat(wcc.getConfiguration()).isNull();
        assertThat(wcc.getProperties()).isEmpty();

        final ComponentConfiguration cc = new ComponentConfiguration("pid", null, null);
        cc.setProperties(null);
        assertThat(cc.getProperties()).isEmpty();
        final Map<String, Object> props = new java.util.HashMap<>();
        props.put("k", "v");
        cc.setProperties(props);
        props.put("k2", "v2");
        assertThat(cc.getProperties()).containsOnlyKeys("k");
    }

    @Test
    void exposeWireConfigurationSetters() {
        final WireConfiguration configuration = new WireConfiguration();
        configuration.setEmitterPid("e");
        configuration.setReceiverPid("r");
        assertThat(configuration.getEmitterPid()).isEqualTo("e");
        assertThat(configuration.getReceiverPid()).isEqualTo("r");
        assertThat(configuration).isEqualTo(new WireConfiguration("e", "r"));
    }

    @Test
    void handleMultiportWireEqualityBranches() {
        final MultiportWireConfiguration wire = new MultiportWireConfiguration("e", "r", 0, 0);
        // 同一实例、null、跨类型与不同 PID 的分支
        assertThat(wire).isEqualTo(wire);
        assertThat(wire).isNotEqualTo(null);
        assertThat(wire).isNotEqualTo(new MultiportWireConfiguration("e2", "r", 0, 0));
        assertThat(wire).isNotEqualTo(new MultiportWireConfiguration("e", "r2", 0, 0));
        assertThat(wire.hashCode()).isEqualTo(new MultiportWireConfiguration("e", "r", 0, 0).hashCode());
    }
}
