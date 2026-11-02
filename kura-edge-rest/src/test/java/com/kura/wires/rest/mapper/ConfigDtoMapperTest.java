/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.mapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.rest.dto.PropertyDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigDtoMapper} 的单元测试：类型推断与密码脱敏。
 */
class ConfigDtoMapperTest {

    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";

    private WireComponentRegistry registry;
    private ConfigDtoMapper mapper;

    @BeforeEach
    void setUp() {
        this.registry = mock(WireComponentRegistry.class);
        final WireComponentFactory factory = mock(WireComponentFactory.class);
        when(factory.getDefinition()).thenReturn(new Ocd(TIMER_FACTORY_PID, "Timer", "定时器",
                List.of(new Ad("password", "password", Scalar.PASSWORD, true, null))));
        when(this.registry.findFactory(TIMER_FACTORY_PID)).thenReturn(Optional.of(factory));
        this.mapper = new ConfigDtoMapper(this.registry);
    }

    @Test
    void masksPasswordDeclaredInOcd() {
        final var configuration = new ComponentConfiguration("timer-1", null,
                Map.of(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID, "password", "s3cret", "name", "t"));

        final var dto = this.mapper.toDto(configuration, true);

        assertThat(dto.getProperties().get("password").getType()).isEqualTo("PASSWORD");
        assertThat(dto.getProperties().get("password").getValue()).isEqualTo(ConfigDtoMapper.PASSWORD_PLACEHOLDER);
        assertThat(dto.getProperties().get("name").getValue()).isEqualTo("t");
    }

    @Test
    void fallsBackToNameHeuristicForUnknownFactory() {
        final var configuration = new ComponentConfiguration("driver-1", null, Map.of("db.password", "s3cret"));

        final var dto = this.mapper.toDto(configuration, true);

        assertThat(dto.getProperties().get("db.password").getValue()).isEqualTo(ConfigDtoMapper.PASSWORD_PLACEHOLDER);
    }

    @Test
    void keepsPlainValueWhenMaskingDisabled() {
        final var configuration = new ComponentConfiguration("driver-1", null, Map.of("db.password", "s3cret"));

        final var dto = this.mapper.toDto(configuration, false);

        assertThat(dto.getProperties().get("db.password").getValue()).isEqualTo("s3cret");
    }

    @Test
    void nonPasswordPropertiesKeepInferredScalarTypes() {
        final var configuration = new ComponentConfiguration("timer-1", null,
                Map.of("simple.interval", 10, "enabled", true, "name", "timer", "ratio", 0.5));

        final var dto = this.mapper.toDto(configuration, true);

        assertThat(dto.getProperties().get("simple.interval").getType()).isEqualTo("INTEGER");
        assertThat(dto.getProperties().get("enabled").getType()).isEqualTo("BOOLEAN");
        assertThat(dto.getProperties().get("name").getType()).isEqualTo("STRING");
        assertThat(dto.getProperties().get("ratio").getType()).isEqualTo("DOUBLE");
    }

    @Test
    void toPropertiesUnwrapsValues() {
        final Map<String, PropertyDto> source = Map.of("a", new PropertyDto("STRING", "x"), "b", new PropertyDto("INTEGER", 7));

        final var result = this.mapper.toProperties(source);

        assertThat(result).containsEntry("a", "x").containsEntry("b", 7);
    }

    @Test
    void isPasswordPropertyChecksOcdBeforeNameHeuristic() {
        assertThat(this.mapper.isPasswordProperty("timer-1", TIMER_FACTORY_PID, "password")).isTrue();
        assertThat(this.mapper.isPasswordProperty("timer-1", TIMER_FACTORY_PID, "name")).isFalse();
        assertThat(this.mapper.isPasswordProperty("timer-1", "", "PASSWORD_FIELD")).isTrue();
    }
}
