/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.snapshot.GraphJsonCodec;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WireGraphSnapshotService} 的单元测试：对照上游
 * updateWireGraphConfiguration 的校验与 FailureHandler 聚合语义。
 * <p>
 * 图 JSON 固定为上游形状（{@code components}/{@code wires}），组件配置属性由独立配置条目携带。
 * </p>
 */
class WireGraphSnapshotServiceTest {

    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";

    private WireGraphService wireGraphService;
    private ComponentConfigurationService configurationService;
    private WireComponentRegistry registry;
    private WireGraphSnapshotService service;

    @BeforeEach
    void setUp() {
        this.wireGraphService = mock(WireGraphService.class);
        this.configurationService = mock(ComponentConfigurationService.class);
        this.registry = mock(WireComponentRegistry.class);
        final WireComponentFactory timerFactory = timerFactoryWithPasswordDefinition();
        when(this.registry.findFactory(TIMER_FACTORY_PID)).thenReturn(Optional.of(timerFactory));
        this.service = new WireGraphSnapshotService(this.wireGraphService, this.configurationService, this.registry,
                new GraphJsonCodec(), new ConfigDtoMapper(this.registry));
    }

    @Test
    void successfulUpdateSnapshotAppliesGraphAndConfigs() {
        final WireGraphConfiguration storedGraph = graphWithTimer();
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(
                List.of(graphServiceConfig(), timerConfig(Map.of("service.factoryPid",
                        new PropertyDto("STRING", TIMER_FACTORY_PID), "simple.interval",
                        new PropertyDto("INTEGER", 30)))));

        this.service.updateSnapshot(request);

        verify(this.wireGraphService).update(any(WireGraphConfiguration.class));
        verify(this.configurationService, never()).updateConfiguration(anyString(), any());
    }

    /** 组件未随请求出现时，配置属性必须从配置服务回填（图 JSON 不含配置属性）。 */
    @Test
    void successfulUpdateSnapshotRestoresStoredConfigurationOfGraphOnlyComponents() {
        when(this.configurationService.getConfiguration("timer-1")).thenReturn(Optional.of(
                new ComponentConfiguration("timer-1", null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID,
                        TIMER_FACTORY_PID, "simple.interval", 10))));

        this.service.updateSnapshot(new ComponentConfigurationListDto(List.of(graphServiceConfig())));

        verify(this.wireGraphService).update(org.mockito.ArgumentMatchers.argThat(argument -> Integer
                .valueOf(10).equals(argument.getWireComponentConfigurations().get(0).getConfiguration().getProperties()
                        .get("simple.interval"))));
        verify(this.configurationService, never()).updateConfiguration(anyString(), any());
    }

    @Test
    void updateSnapshotRestoresPasswordPlaceholdersForGraphComponents() {
        when(this.configurationService.getConfiguration("timer-1"))
                .thenReturn(Optional.of(new ComponentConfiguration("timer-1", null, Map.of("password", "s3cret"))));
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(List.of(graphServiceConfig(),
                timerConfig(Map.of("service.factoryPid", new PropertyDto("STRING", TIMER_FACTORY_PID), "password",
                        new PropertyDto("PASSWORD", ConfigDtoMapper.PASSWORD_PLACEHOLDER)))));

        this.service.updateSnapshot(request);

        verify(this.wireGraphService).update(org.mockito.ArgumentMatchers.argThat(
                argument -> "s3cret".equals(argument.getWireComponentConfigurations().get(0).getConfiguration()
                        .getProperties().get("password"))));
    }

    @Test
    void updateSnapshotWithoutGraphServiceConfigIsRejected() {
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(List.of(timerConfig(Map.of())));

        assertThatThrownBy(() -> this.service.updateSnapshot(request)).isInstanceOfSatisfying(WiresException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFIG_INVALID));
    }

    @Test
    void updateSnapshotWithNonStringWireGraphPropertyIsRejected() {
        final ComponentConfigurationDto invalidGraphServiceConfig = new ComponentConfigurationDto(
                WireGraphConstants.WIRE_GRAPH_SERVICE_PID, null,
                Map.of(WireGraphConstants.WIRE_GRAPH_PROPERTY, new PropertyDto("INTEGER", 42)));

        assertThatThrownBy(() -> this.service.updateSnapshot(new ComponentConfigurationListDto(
                List.of(invalidGraphServiceConfig)))).isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFIG_INVALID));
    }

    @Test
    void updateSnapshotWithInvalidGraphJsonIsRejected() {
        final ComponentConfigurationDto invalidGraphServiceConfig = new ComponentConfigurationDto(
                WireGraphConstants.WIRE_GRAPH_SERVICE_PID, null,
                Map.of(WireGraphConstants.WIRE_GRAPH_PROPERTY, new PropertyDto("STRING", "{not-json")));

        assertThatThrownBy(() -> this.service.updateSnapshot(new ComponentConfigurationListDto(
                List.of(invalidGraphServiceConfig)))).isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_INVALID));
    }

    @Test
    void updateSnapshotWithUnknownFactoryPidIsRejected() {
        final ComponentConfigurationListDto request = new ComponentConfigurationListDto(
                List.of(graphServiceConfig(), timerConfig(Map.of("service.factoryPid",
                        new PropertyDto("STRING", "org.eclipse.kura.wire.Unknown")))));

        assertThatThrownBy(() -> this.service.updateSnapshot(request)).isInstanceOfSatisfying(WiresException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFIG_INVALID));
    }

    @Test
    void updateSnapshotAggregatesGraphUpdateFailure() {
        doThrow(new WiresException(ErrorCode.ENGINE_ERROR, "engine broke")).when(this.wireGraphService)
                .update(any());

        assertThatThrownBy(() -> this.service.updateSnapshot(new ComponentConfigurationListDto(
                List.of(graphServiceConfig())))).isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENGINE_ERROR));
    }

    @Test
    void updateSnapshotPropagatesValidationFailureDirectly() {
        doThrow(new WiresException(ErrorCode.TOPOLOGY_CYCLE, "cycle detected")).when(this.wireGraphService)
                .update(any());

        assertThatThrownBy(() -> this.service.updateSnapshot(new ComponentConfigurationListDto(
                List.of(graphServiceConfig())))).isInstanceOfSatisfying(WiresException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOPOLOGY_CYCLE));
    }

    @Test
    void successfulGetSnapshotReturnsGraphServiceConfigFirst() {
        when(this.wireGraphService.get()).thenReturn(graphWithTimer());

        final var snapshot = this.service.getSnapshot();

        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).getPid()).isEqualTo(WireGraphConstants.WIRE_GRAPH_SERVICE_PID);
        assertThat(snapshot.get(0).getProperties().get(WireGraphConstants.WIRE_GRAPH_PROPERTY).getType())
                .isEqualTo("STRING");
        // 图 JSON 与上游一致：components/wires，且不含组件配置属性（密码不会从这条路径泄漏）
        assertThat((String) snapshot.get(0).getProperties().get(WireGraphConstants.WIRE_GRAPH_PROPERTY).getValue())
                .contains("\"components\"").contains("\"wires\"").doesNotContain("wireComponentConfigurations")
                .doesNotContain("s3cret");
        assertThat(snapshot.get(1).getPid()).isEqualTo("timer-1");
        assertThat(snapshot.get(1).getDefinition()).isNull();
    }

    @Test
    void getSnapshotMasksPasswordProperties() {
        when(this.wireGraphService.get()).thenReturn(graphWithTimer());

        final var snapshot = this.service.getSnapshot();

        assertThat(snapshot.get(1).getProperties().get("password").getValue())
                .isEqualTo(ConfigDtoMapper.PASSWORD_PLACEHOLDER);
    }

    @Test
    void successfulDeleteGraphDelegatesToGraphService() {
        this.service.deleteGraph();

        verify(this.wireGraphService).delete();
    }

    private static WireComponentFactory timerFactoryWithPasswordDefinition() {
        final WireComponentFactory factory = mock(WireComponentFactory.class);
        when(factory.factoryPid()).thenReturn(TIMER_FACTORY_PID);
        final Ad passwordAd = new Ad("password", "password", Scalar.PASSWORD, true, null);
        final Ocd ocd = new Ocd(TIMER_FACTORY_PID, "Timer", "定时器", List.of(passwordAd));
        when(factory.getDefinition()).thenReturn(ocd);
        return factory;
    }

    private static WireGraphConfiguration graphWithTimer() {
        return new WireGraphConfiguration(List.of(new WireComponentConfiguration(new ComponentConfiguration("timer-1",
                null, Map.of(WireGraphConstants.SERVICE_FACTORY_PID, TIMER_FACTORY_PID, "password", "s3cret")),
                Map.of(WireGraphConstants.INPUT_PORT_COUNT, 0, WireGraphConstants.OUTPUT_PORT_COUNT, 1))), List.of());
    }

    private static ComponentConfigurationDto graphServiceConfig() {
        return new ComponentConfigurationDto(WireGraphConstants.WIRE_GRAPH_SERVICE_PID, null, Map
                .of(WireGraphConstants.WIRE_GRAPH_PROPERTY, new PropertyDto("STRING",
                        "{\"components\":[{\"pid\":\"timer-1\",\"inputPortCount\":0,\"outputPortCount\":1,"
                                + "\"renderingProperties\":{\"position\":{\"x\":0.0,\"y\":0.0}}}],\"wires\":[]}")));
    }

    private static ComponentConfigurationDto timerConfig(final Map<String, PropertyDto> properties) {
        return new ComponentConfigurationDto("timer-1", null, properties);
    }
}
