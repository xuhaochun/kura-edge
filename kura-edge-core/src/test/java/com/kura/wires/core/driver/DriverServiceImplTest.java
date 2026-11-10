/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kura.wires.core.testsupport.InMemoryComponentConfigurationService;
import com.kura.wires.engine.DriverFactory;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 驱动服务的行为测试：工厂注册、实例创建/更新/删除、重启恢复与非法输入拒绝。
 * <p>
 * 用内存配置服务 + 最小驱动工厂，断言的是「配置与实例是否一致」这一可观察行为，
 * 不关心服务内部用哪个 Map 存实例。
 * </p>
 */
public class DriverServiceImplTest {

    private static final String FACTORY_PID = "org.eclipse.kura.driver.simulated";
    private static final String DRIVER_PID = "driver-1";

    private InMemoryComponentConfigurationService configurationService;
    private DriverServiceImpl service;

    @BeforeEach
    public void setUp() {
        this.configurationService = new InMemoryComponentConfigurationService();
        this.service = new DriverServiceImpl(List.of(new StubDriverFactory()), this.configurationService);
    }

    @Test
    public void listRegisteredDriverFactories() {
        assertThat(this.service.getDriverFactoryPids()).containsExactly(FACTORY_PID);
        assertThat(this.service.isDriverFactory(FACTORY_PID)).isTrue();
        assertThat(this.service.isDriverFactory("other")).isFalse();
        assertThat(this.service.getDefinition(FACTORY_PID)).isPresent();
        assertThat(this.service.getChannelDescriptor(FACTORY_PID)).extracting(Ad::getId)
                .containsExactly("simulated.register");
    }

    @Test
    public void createDriverPersistsConfigurationAndInstance() {
        whenCreatingDriver(FACTORY_PID, DRIVER_PID, Map.of("simulated.description", "demo"));

        thenDriverExists(DRIVER_PID);
        assertThat(this.service.findDriver(DRIVER_PID)).isPresent();
        assertThat(this.configurationService.getConfiguration(DRIVER_PID).orElseThrow().getProperties())
                .containsEntry(WireGraphConstants.SERVICE_FACTORY_PID, FACTORY_PID)
                .containsEntry("simulated.description", "demo");
    }

    @Test
    public void fillRequiredDefaultsOnCreate() {
        whenCreatingDriver(FACTORY_PID, DRIVER_PID, Map.of());

        assertThat(this.configurationService.getConfiguration(DRIVER_PID).orElseThrow().getProperties())
                .containsEntry("simulated.mode", "DEFAULT");
    }

    @Test
    public void rejectCreateWithUnknownFactory() {
        assertThatThrownBy(() -> whenCreatingDriver("org.eclipse.kura.driver.unknown", DRIVER_PID, Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_INVALID);
    }

    @Test
    public void rejectCreateWithDuplicatePid() {
        whenCreatingDriver(FACTORY_PID, DRIVER_PID, Map.of());

        assertThatThrownBy(() -> whenCreatingDriver(FACTORY_PID, DRIVER_PID, Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_INVALID);
    }

    @Test
    public void rebuildInstanceOnUpdate() {
        whenCreatingDriver(FACTORY_PID, DRIVER_PID, Map.of());
        final StubDriver before = (StubDriver) this.service.findDriver(DRIVER_PID).orElseThrow();

        this.service.updateDriver(DRIVER_PID, Map.of("simulated.description", "updated"));

        final StubDriver after = (StubDriver) this.service.findDriver(DRIVER_PID).orElseThrow();
        assertThat(after).isNotSameAs(before);
        assertThat(after.getProperties()).containsEntry("simulated.description", "updated");
    }

    @Test
    public void rejectUpdateOfUnknownDriver() {
        assertThatThrownBy(() -> this.service.updateDriver("nope", Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(error -> ((WiresException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void deleteDriverRemovesConfigurationAndInstance() {
        whenCreatingDriver(FACTORY_PID, DRIVER_PID, Map.of());

        this.service.deleteDriver(DRIVER_PID);

        thenDriverExists(DRIVER_PID, false);
        assertThat(this.service.findDriver(DRIVER_PID)).isEmpty();
    }

    @Test
    public void restoreInstancesFromPersistedConfigurations() {
        this.configurationService.createFactoryConfiguration(FACTORY_PID, "restored",
                Map.of(WireGraphConstants.SERVICE_FACTORY_PID, FACTORY_PID));
        final DriverServiceImpl restarted = new DriverServiceImpl(List.of(new StubDriverFactory()),
                this.configurationService);

        restarted.reloadAll();

        assertThat(restarted.findDriver("restored")).isPresent();
        assertThat(restarted.getDriverPids()).contains("restored");
    }

    @Test
    public void ignoreConfigurationsOfOtherFactoriesWhenReloading() {
        this.configurationService.createFactoryConfiguration("org.eclipse.kura.wire.Timer", "timer-1", Map.of());

        this.service.reloadAll();

        assertThat(this.service.findDriver("timer-1")).isEmpty();
        assertThat(this.service.getDriverPids()).isEmpty();
    }

    private void whenCreatingDriver(final String factoryPid, final String pid, final Map<String, Object> properties) {
        this.service.createDriver(factoryPid, pid, properties);
    }

    private void thenDriverExists(final String pid) {
        thenDriverExists(pid, true);
    }

    private void thenDriverExists(final String pid, final boolean expected) {
        assertThat(this.service.exists(pid)).isEqualTo(expected);
    }

    /** 最小驱动工厂：只用来验证服务的生命周期编排 */
    private static final class StubDriverFactory implements DriverFactory {

        @Override
        public String factoryPid() {
            return FACTORY_PID;
        }

        @Override
        public Ocd getDefinition() {
            return new Ocd(FACTORY_PID, "Stub", "stub driver",
                    List.of(new Ad("simulated.mode", "simulated.mode", Scalar.STRING, true, "DEFAULT")));
        }

        @Override
        public ChannelDescriptor getChannelDescriptor() {
            return () -> List.of(new Ad("simulated.register", "simulated.register", Scalar.STRING, true, "r"));
        }

        @Override
        public Driver create(final String pid, final Map<String, Object> properties) {
            return new StubDriver(pid, properties);
        }
    }

    /** 最小驱动：只记录配置 */
    private static final class StubDriver implements Driver {

        private final String pid;

        private final Map<String, Object> properties;

        StubDriver(final String pid, final Map<String, Object> properties) {
            this.pid = pid;
            this.properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
        }

        String getPid() {
            return this.pid;
        }

        Map<String, Object> getProperties() {
            return this.properties;
        }

        @Override
        public void connect() {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public ChannelDescriptor getChannelDescriptor() {
            return () -> List.of();
        }

        @Override
        public void read(final List<com.kura.wires.model.channel.ChannelRecord> records) {
            // no-op
        }

        @Override
        public void write(final List<com.kura.wires.model.channel.ChannelRecord> records) {
            // no-op
        }

        @Override
        public void registerChannelListener(final Map<String, Object> channelConfig,
                final com.kura.wires.model.channel.listener.ChannelListener listener) {
            // no-op
        }

        @Override
        public void unregisterChannelListener(final com.kura.wires.model.channel.listener.ChannelListener listener) {
            // no-op
        }
    }
}
