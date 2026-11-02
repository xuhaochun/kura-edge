/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.kura.wires.core.asset.AssetService;
import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.driver.DriverService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireGraphService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.ComponentConfigurationListDto;
import com.kura.wires.rest.dto.PropertyDto;
import com.kura.wires.rest.mapper.ConfigDtoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WireConfigService} 的单元测试：对照上游 configs/byPid 与
 * updateConfigurations 端点的校验与聚合语义。
 */
class WireConfigServiceTest {

    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";

    private WireGraphService wireGraphService;
    private ComponentConfigurationService configurationService;
    private WireComponentRegistry registry;
    private DriverService driverService;
    private AssetService assetService;
    private WireConfigService service;

    @BeforeEach
    void setUp() {
        this.wireGraphService = mock(WireGraphService.class);
        this.configurationService = mock(ComponentConfigurationService.class);
        this.registry = mock(WireComponentRegistry.class);
        this.driverService = mock(DriverService.class);
        this.assetService = mock(AssetService.class);
        final WireComponentFactory timerFactory = timerFactoryWithPasswordDefinition();
        when(this.registry.findFactory(TIMER_FACTORY_PID)).thenReturn(Optional.of(timerFactory));
        when(this.wireGraphService.get()).thenReturn(graphReferencingTimer());
        this.service = new WireConfigService(this.wireGraphService, this.configurationService, this.registry,
                this.driverService, this.assetService, new ConfigDtoMapper(this.registry));
    }

    @Test
    void successfulGetConfigsByPidsMasksPasswords() {
        when(this.configurationService.getConfiguration("timer-1"))
                .thenReturn(Optional.of(new ComponentConfiguration("timer-1", null,
                        Map.of(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID, "password", "s3cret"))));

        final var result = this.service.getConfigsByPids(Set.of("timer-1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProperties().get("password").getType()).isEqualTo("PASSWORD");
        assertThat(result.get(0).getProperties().get("password").getValue())
                .isEqualTo(ConfigDtoMapper.PASSWORD_PLACEHOLDER);
    }

    @Test
    void getConfigsByPidsSkipsMissingPids() {
        when(this.configurationService.getConfiguration("missing")).thenReturn(Optional.empty());

        assertThat(this.service.getConfigsByPids(Set.of("missing"))).isEmpty();
    }

    @Test
    void deleteConfigsByPidsRejectsReferencedPid() {
        assertThatThrownBy(() -> this.service.deleteConfigsByPids(Set.of("timer-1")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_REFERENCED));
        verify(this.configurationService, never()).deleteFactoryConfiguration(anyString());
    }

    @Test
    void deleteConfigsByPidsRejectsUnknownPid() {
        when(this.configurationService.exists("unknown")).thenReturn(false);

        assertThatThrownBy(() -> this.service.deleteConfigsByPids(Set.of("unknown")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void successfulDeleteConfigsByPids() {
        when(this.configurationService.exists("standalone-1")).thenReturn(true);

        assertThatCode(() -> this.service.deleteConfigsByPids(Set.of("standalone-1"))).doesNotThrowAnyException();
        verify(this.configurationService).deleteFactoryConfiguration("standalone-1");
    }

    @Test
    void deleteConfigsByPidsAggregatesFailures() {
        when(this.configurationService.exists(anyString())).thenReturn(true);
        doThrow(new WiresException(ErrorCode.PERSISTENCE_ERROR, "db down")).when(this.configurationService)
                .deleteFactoryConfiguration("broken-1");

        assertThatThrownBy(() -> this.service.deleteConfigsByPids(Set.of("broken-1")))
                .isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENGINE_ERROR));
    }

    @Test
    void successfulUpdateConfigs() {
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(List.of(new ComponentConfigurationDto(
                "timer-1", null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID, new PropertyDto("STRING", TIMER_FACTORY_PID),
                        "simple.interval", new PropertyDto("INTEGER", 30)))));

        this.service.updateConfigs(request);

        verify(this.configurationService).updateConfiguration(eq("timer-1"),
                argThat(properties -> Integer.valueOf(30).equals(properties.get("simple.interval"))));
    }

    @Test
    void updateConfigsRestoresPasswordPlaceholders() {
        when(this.configurationService.getConfiguration("timer-1"))
                .thenReturn(Optional.of(new ComponentConfiguration("timer-1", null, Map.of("password", "s3cret"))));
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(List.of(
                new ComponentConfigurationDto("timer-1", null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID,
                        new PropertyDto("STRING", TIMER_FACTORY_PID), "password",
                        new PropertyDto("PASSWORD", ConfigDtoMapper.PASSWORD_PLACEHOLDER)))));

        this.service.updateConfigs(request);

        verify(this.configurationService).updateConfiguration(eq("timer-1"),
                argThat(properties -> "s3cret".equals(properties.get("password"))));
    }

    @Test
    void updateConfigsRejectsUnreferencedWireComponent() {
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(List.of(
                new ComponentConfigurationDto("stray-1", null,
                        Map.of(WireGraphConstants.SERVICE_FACTORY_PID, new PropertyDto("STRING", TIMER_FACTORY_PID)))));

        assertThatThrownBy(() -> this.service.updateConfigs(request)).isInstanceOfSatisfying(WiresException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFIG_INVALID));
        verify(this.configurationService, never()).updateConfiguration(anyString(), any());
    }

    @Test
    void updateConfigsRejectsMissingFactoryPid() {
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(
                List.of(new ComponentConfigurationDto("timer-1", null, Map.of("simple.interval",
                        new PropertyDto("INTEGER", 30)))));

        assertThatThrownBy(() -> this.service.updateConfigs(request)).isInstanceOfSatisfying(WiresException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFIG_INVALID));
    }

    @Test
    void updateConfigsAllowsStandaloneNonWireComponentConfig() {
        when(this.registry.findFactory("com.example.Driver")).thenReturn(Optional.empty());
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(List.of(
                new ComponentConfigurationDto("driver-1", null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID,
                        new PropertyDto("STRING", "com.example.Driver")))));

        this.service.updateConfigs(request);

        verify(this.configurationService).updateConfiguration(eq("driver-1"), any());
    }

    private static WireComponentFactory timerFactoryWithPasswordDefinition() {
        final WireComponentFactory factory = mock(WireComponentFactory.class);
        when(factory.factoryPid()).thenReturn(TIMER_FACTORY_PID);
        when(factory.getDefinition()).thenReturn(new Ocd(TIMER_FACTORY_PID, "Timer", "定时器",
                List.of(new Ad("password", "password", Scalar.PASSWORD, true, null))));
        return factory;
    }

    private static WireGraphConfiguration graphReferencingTimer() {
        return new WireGraphConfiguration(List.of(new WireComponentConfiguration(new ComponentConfiguration("timer-1",
                null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID)), Map.of())), List.of());
    }
}
