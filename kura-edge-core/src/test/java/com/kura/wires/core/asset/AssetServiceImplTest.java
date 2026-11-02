/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.core.testsupport.InMemoryComponentConfigurationService;
import com.kura.wires.core.testsupport.StubDriverService;
import com.kura.wires.core.testsupport.StubAssetFactory;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Scalar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 资产服务的行为测试：实例创建/更新/删除、必需参数校验、默认值回填、重启恢复与描述符拼装。
 */
public class AssetServiceImplTest {

    private static final String ASSET_PID = "asset-1";
    private static final String DRIVER_PID = "driver-1";

    private InMemoryComponentConfigurationService configurationService;
    private StubDriverService driverService;
    private WireComponentRegistry registry;
    private AssetServiceImpl service;

    @BeforeEach
    public void setUp() {
        this.configurationService = new InMemoryComponentConfigurationService();
        this.registry = new WireComponentRegistry(List.of(new StubAssetFactory()));
        this.driverService = new StubDriverService()
                .withDriver("driver-1", "org.eclipse.kura.driver.simulated", null)
                .withDescriptor("org.eclipse.kura.driver.simulated",
                        List.of(new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "r")));
        this.service = new AssetServiceImpl(this.registry, this.configurationService, this.driverService);
    }

    @Test
    public void createAssetPersistsConfigurationAndInstance() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));

        assertThat(this.service.exists(ASSET_PID)).isTrue();
        assertThat(this.service.findAsset(ASSET_PID)).isPresent();
        assertThat(this.configurationService.getConfiguration(ASSET_PID).orElseThrow().getProperties())
                .containsEntry(WireGraphConstants.SERVICE_FACTORY_PID, AssetConstants.ASSET_FACTORY_PID)
                .containsEntry(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID);
    }

    @Test
    public void rejectCreateWithoutDriverPid() {
        assertThatThrownBy(() -> whenCreatingAsset(Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_INVALID);
    }

    @Test
    public void rejectCreateWithDuplicatePid() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));

        assertThatThrownBy(() -> whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID)))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_INVALID);
    }

    @Test
    public void rebuildInstanceOnUpdate() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));
        final Asset before = this.service.findAsset(ASSET_PID).orElseThrow();

        this.service.updateAsset(ASSET_PID, withChannel("ch", "READ", "INTEGER"));

        final Asset after = this.service.findAsset(ASSET_PID).orElseThrow();
        assertThat(after).isNotSameAs(before);
        assertThat(after.getAssetConfiguration().getAssetChannels()).containsKey("ch");
        assertThat(this.service.getAssetProperties(ASSET_PID).orElseThrow())
                .containsEntry("ch#+type", "READ")
                .containsEntry(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID);
    }

    @Test
    public void keepDriverPidWhenUpdateOmitsIt() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));

        this.service.updateAsset(ASSET_PID, withChannel("ch", "READ", "INTEGER"));

        assertThat(this.service.getAssetConfiguration(ASSET_PID).orElseThrow().getDriverPid())
                .isEqualTo(DRIVER_PID);
    }

    @Test
    public void rejectUpdateOfUnknownAsset() {
        assertThatThrownBy(() -> this.service.updateAsset("nope", Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID)))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void deleteAssetRemovesConfigurationAndInstance() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));

        this.service.deleteAsset(ASSET_PID);

        assertThat(this.service.exists(ASSET_PID)).isFalse();
        assertThat(this.registry.getInstance(ASSET_PID)).isEmpty();
    }

    @Test
    public void listAssetsFromPersistedConfigurations() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));
        this.configurationService.createFactoryConfiguration("org.eclipse.kura.wire.Timer", "timer-1", Map.of());

        assertThat(this.service.getAssetPids()).containsExactly(ASSET_PID);
    }

    @Test
    public void restoreInstancesFromPersistedConfigurations() {
        this.configurationService.createFactoryConfiguration(AssetConstants.ASSET_FACTORY_PID, "restored",
                Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));

        this.service.reloadAll();

        assertThat(this.service.findAsset("restored")).isPresent();
    }

    @Test
    public void exposeBaseChannelDescriptorWithWireAssetListenParameter() {
        final List<Ad> base = this.service.getBaseChannelDescriptor();

        assertThat(base).extracting(Ad::getId).containsExactly("+enabled", "+name", "+type", "+value.type",
                "+scaleoffset.type", "+scale", "+offset", "+unit", "+listen");
        assertThat(base.get(base.size() - 1).getName()).isEqualTo("listen");
    }

    @Test
    public void exposeAssetDefinitionAndDescriptorWithoutDriver() {
        whenCreatingAsset(Map.of(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID));

        assertThat(this.service.getAssetDefinition()).isPresent();
        assertThat(this.service.getChannelDescriptor(ASSET_PID)).extracting(Ad::getId)
                .containsExactly("+enabled", "+name", "+type", "+value.type", "+scaleoffset.type", "+scale", "+offset",
                        "+unit", "+listen", "simulated.register");
    }

    @Test
    public void returnEmptyAssetWhenInstanceIsNotRunning() {
        assertThat(this.service.findAsset("missing")).isEmpty();
        assertThat(this.service.getAssetProperties("missing")).isEmpty();
        assertThat(this.service.getAssetConfiguration("missing")).isEmpty();
    }

    private void whenCreatingAsset(final Map<String, Object> properties) {
        this.service.createAsset(ASSET_PID, properties);
    }

    private Map<String, Object> withChannel(final String name, final String type, final String valueType) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(AssetConstants.ASSET_DRIVER_PROP, DRIVER_PID);
        properties.put(name + "#+type", type);
        properties.put(name + "#+value.type", valueType);
        return properties;
    }
}
