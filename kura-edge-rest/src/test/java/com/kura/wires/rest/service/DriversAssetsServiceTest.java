/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.asset.AssetService;
import com.kura.wires.core.config.ComponentConfigurationService;
import com.kura.wires.core.driver.DriverService;
import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireGraphService;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.channel.Channel;
import com.kura.wires.model.channel.ChannelType;
import com.kura.wires.model.channel.ScaleOffsetType;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.type.DataType;
import com.kura.wires.rest.dto.CreateAssetRequestDto;
import com.kura.wires.rest.dto.CreateDriverRequestDto;
import com.kura.wires.rest.dto.PidAndFactoryPidDto;
import com.kura.wires.rest.dto.WireGraphMetadataDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link DriversAssetsService} 的行为测试：列表投影、创建时的属性拼装与校验、
 * 删除的引用保护（409003）、元数据汇总与配置查询的 404。
 */
public class DriversAssetsServiceTest {

    private static final String DRIVER_PID = "driver-1";
    private static final String ASSET_PID = "asset-1";
    private static final String DRIVER_FACTORY = "org.eclipse.kura.driver.simulated";

    private DriverService driverService;
    private AssetService assetService;
    private WireGraphService wireGraphService;
    private WireComponentRegistry registry;
    private ComponentConfigurationService configurationService;
    private DriversAssetsService service;

    @BeforeEach
    public void setUp() {
        this.driverService = mock(DriverService.class);
        this.assetService = mock(AssetService.class);
        this.wireGraphService = mock(WireGraphService.class);
        this.registry = mock(WireComponentRegistry.class);
        this.configurationService = mock(ComponentConfigurationService.class);
        this.service = new DriversAssetsService(this.driverService, this.assetService, this.wireGraphService,
                this.registry, this.configurationService);
        when(this.wireGraphService.get()).thenReturn(emptyGraph());
        when(this.assetService.getBaseChannelDescriptor()).thenReturn(
                List.of(new Ad("+enabled", "enabled", Scalar.BOOLEAN, true, true)));
    }

    @Test
    public void listDriversWithFactoryPid() {
        when(this.driverService.getDriverPids()).thenReturn(List.of(DRIVER_PID));
        when(this.driverService.getFactoryPid(DRIVER_PID)).thenReturn(Optional.of(DRIVER_FACTORY));

        final List<PidAndFactoryPidDto> drivers = this.service.listDrivers();

        assertThat(drivers).hasSize(1);
        assertThat(drivers.get(0).getPid()).isEqualTo(DRIVER_PID);
        assertThat(drivers.get(0).getFactoryPid()).isEqualTo(DRIVER_FACTORY);
    }

    @Test
    public void listAssetsWithDriverPidAndDescription() {
        when(this.assetService.getAssetPids()).thenReturn(List.of(ASSET_PID));
        when(this.assetService.getAssetConfiguration(ASSET_PID)).thenReturn(Optional.of(
                new AssetConfiguration("demo", DRIVER_PID, Map.of())));

        final List<PidAndFactoryPidDto> assets = this.service.listAssets();

        assertThat(assets).hasSize(1);
        assertThat(assets.get(0).getFactoryPid()).isEqualTo(AssetConstants.ASSET_FACTORY_PID);
        assertThat(assets.get(0).getDriverPid()).isEqualTo(DRIVER_PID);
        assertThat(assets.get(0).getDescription()).isEqualTo("demo");
    }

    @Test
    public void createDriverDelegatesToDriverService() {
        final CreateDriverRequestDto request = new CreateDriverRequestDto();
        request.setFactoryPid(DRIVER_FACTORY);
        request.setPid(DRIVER_PID);

        this.service.createDriver(request);

        verify(this.driverService).createDriver(DRIVER_FACTORY, DRIVER_PID, null);
    }

    @Test
    public void createAssetBindsDriverPidAndFillsOcdDefaults() {
        final CreateAssetRequestDto request = new CreateAssetRequestDto();
        request.setPid(ASSET_PID);
        request.setDriverPid(DRIVER_PID);
        request.setDescription("demo");
        when(this.driverService.exists(DRIVER_PID)).thenReturn(true);
        when(this.assetService.getAssetDefinition()).thenReturn(Optional.of(new Ocd(
                AssetConstants.ASSET_FACTORY_PID, "Wire Asset", "asset",
                List.of(new Ad("emit.errors", "emit.errors", Scalar.BOOLEAN, true, Boolean.FALSE)))));

        this.service.createAsset(request);

        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(this.assetService).createAsset(org.mockito.ArgumentMatchers.eq(ASSET_PID), captor.capture());
        assertThat(captor.getValue()).containsEntry(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID)
                .containsEntry(AssetConstants.ASSET_DESC_PROP, "demo")
                .containsEntry("emit.errors", Boolean.FALSE);
    }

    @Test
    public void rejectCreateAssetWithUnknownDriver() {
        final CreateAssetRequestDto request = new CreateAssetRequestDto();
        request.setPid(ASSET_PID);
        request.setDriverPid("missing");
        when(this.driverService.exists("missing")).thenReturn(false);

        assertThatThrownBy(() -> this.service.createAsset(request)).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void rejectDeleteDriverStillUsedByAssets() {
        when(this.assetService.getAssetPids()).thenReturn(List.of(ASSET_PID));
        when(this.assetService.getAssetConfiguration(ASSET_PID)).thenReturn(Optional.of(
                new AssetConfiguration("demo", DRIVER_PID, Map.of())));

        assertThatThrownBy(() -> this.service.deleteDriver(DRIVER_PID)).isInstanceOf(WiresException.class)
                .extracting(Throwable::getMessage).asString().contains(ASSET_PID);
        assertThatThrownBy(() -> this.service.deleteDriver(DRIVER_PID)).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_REFERENCED);
    }

    @Test
    public void deleteDriverWithoutAssets() {
        when(this.assetService.getAssetPids()).thenReturn(List.of());

        this.service.deleteDriver(DRIVER_PID);

        verify(this.driverService).deleteDriver(DRIVER_PID);
    }

    @Test
    public void rejectDeleteAssetReferencedByGraph() {
        when(this.wireGraphService.get()).thenReturn(graphReferencing(ASSET_PID));

        assertThatThrownBy(() -> this.service.deleteAsset(ASSET_PID)).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_REFERENCED);
    }

    @Test
    public void deleteAssetNotReferencedByGraph() {
        this.service.deleteAsset(ASSET_PID);

        verify(this.assetService).deleteAsset(ASSET_PID);
    }

    @Test
    public void summarizeMetadataWithAllFourLists() {
        when(this.registry.getFactoryPids()).thenReturn(List.of());
        when(this.driverService.getDriverFactoryPids()).thenReturn(List.of(DRIVER_FACTORY));
        when(this.driverService.getDefinition(DRIVER_FACTORY)).thenReturn(
                Optional.of(new Ocd(DRIVER_FACTORY, "Simulated", "driver", List.of())));
        when(this.driverService.getDriverPids()).thenReturn(List.of(DRIVER_PID));
        when(this.driverService.getFactoryPid(DRIVER_PID)).thenReturn(Optional.of(DRIVER_FACTORY));
        when(this.driverService.getChannelDescriptor(DRIVER_FACTORY))
                .thenReturn(List.of(new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "r")));

        final WireGraphMetadataDto metadata = this.service.getMetadata();

        assertThat(metadata.getDriverOCDs()).hasSize(1);
        assertThat(metadata.getDriverOCDs().get(0).getPid()).isEqualTo(DRIVER_FACTORY);
        assertThat(metadata.getDriverOCDs().get(0).getDefinition()).isNotNull();
        assertThat(metadata.getDriverChannelDescriptors()).hasSize(1);
        assertThat(metadata.getDriverChannelDescriptors().get(0).getChannelDescriptor()).hasSize(1);
        assertThat(metadata.getAssetChannelDescriptor()).hasSize(1);
        assertThat(metadata.getWireComponentDefinitions()).isNull();
    }

    @Test
    public void provideAssetPropertiesAndDescriptor() {
        when(this.assetService.getAssetProperties(ASSET_PID))
                .thenReturn(Optional.of(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID)));
        when(this.assetService.getChannelDescriptor(ASSET_PID))
                .thenReturn(List.of(new Ad("+enabled", "enabled", Scalar.BOOLEAN, true, true)));

        assertThat(this.service.getAssetProperties(ASSET_PID))
                .containsEntry(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID);
        assertThat(this.service.getAssetChannelDescriptor(ASSET_PID)).hasSize(1);
    }

    @Test
    public void returnNotFoundForMissingAssetProperties() {
        when(this.assetService.getAssetProperties("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.getAssetProperties("missing")).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void provideDriverPropertiesAndChannelDescriptor() {
        when(this.driverService.exists(DRIVER_PID)).thenReturn(true);
        when(this.configurationService.getConfiguration(DRIVER_PID)).thenReturn(Optional.of(
                new ComponentConfiguration(DRIVER_PID, null, Map.of("simulated.description", "demo"))));
        when(this.driverService.getFactoryPid(DRIVER_PID)).thenReturn(Optional.of(DRIVER_FACTORY));
        when(this.driverService.getChannelDescriptor(DRIVER_FACTORY)).thenReturn(List.of());
        when(this.driverService.getDriverFactoryPids()).thenReturn(List.of(DRIVER_FACTORY));

        assertThat(this.service.getDriverProperties(DRIVER_PID)).containsEntry("simulated.description", "demo");
        assertThat(this.service.getDriverChannelDescriptor(DRIVER_PID)).isEmpty();
        assertThat(this.service.getComponentFactoryPids()).isEmpty();
        assertThat(this.service.getDriverFactoryPids()).contains(DRIVER_FACTORY);
    }

    @Test
    public void rejectDriverPropertiesForUnknownDriver() {
        when(this.driverService.exists("missing")).thenReturn(false);

        assertThatThrownBy(() -> this.service.getDriverProperties("missing")).isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void updateDriverAndAssetDelegateToTheirServices() {
        this.service.updateDriver(DRIVER_PID, Map.of("a", 1));
        this.service.updateAsset(ASSET_PID, Map.of("b", 2));

        verify(this.driverService).updateDriver(DRIVER_PID, Map.of("a", 1));
        verify(this.assetService).updateAsset(ASSET_PID, Map.of("b", 2));
    }

    @Test
    public void exposeAssetsOfDriver() {
        when(this.assetService.getAssetPids()).thenReturn(List.of(ASSET_PID));
        when(this.assetService.getAssetConfiguration(ASSET_PID)).thenReturn(Optional.of(
                new AssetConfiguration("demo", DRIVER_PID, Map.of("ch", new Channel("ch", ChannelType.READ,
                        DataType.INTEGER, ScaleOffsetType.DEFINED_BY_VALUE_TYPE, 1.0d, 0.0d, Map.of())))));

        assertThat(this.service.assetsOfDriver(DRIVER_PID)).containsExactly(ASSET_PID);
        assertThat(this.service.assetsOfDriver("other")).isEmpty();
    }

    @Test
    public void propagateDeleteAssetFailure() {
        doThrow(new WiresException(ErrorCode.NOT_FOUND, "missing")).when(this.assetService).deleteAsset("missing");

        assertThatThrownBy(() -> this.service.deleteAsset("missing")).isInstanceOf(WiresException.class);
    }

    private static WireGraphConfiguration graphReferencing(final String pid) {
        final ComponentConfiguration configuration = new ComponentConfiguration();
        configuration.setPid(pid);
        configuration.setProperties(Map.of(WireGraphConstants.SERVICE_FACTORY_PID, AssetConstants.ASSET_FACTORY_PID));
        return new WireGraphConfiguration(
                List.of(new WireComponentConfiguration(configuration, Map.of())),
                List.<MultiportWireConfiguration>of());
    }

    private static WireGraphConfiguration emptyGraph() {
        return new WireGraphConfiguration(List.of(), List.of());
    }
}
