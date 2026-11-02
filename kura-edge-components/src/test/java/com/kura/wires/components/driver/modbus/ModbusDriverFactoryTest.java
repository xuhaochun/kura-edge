/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.driver.Driver;

/** Modbus 驱动工厂：PID、OCD、通道描述符与实例创建。 */
class ModbusDriverFactoryTest {

    private final ModbusDriverFactory factory = new ModbusDriverFactory();

    @Test
    public void exposeFactoryPidAndDefinition() {
        final Ocd definition = this.factory.getDefinition();

        assertThat(this.factory.factoryPid()).isEqualTo("org.eclipse.kura.driver.modbus");
        assertThat(definition.getId()).isEqualTo("org.eclipse.kura.driver.modbus");
        assertThat(definition.getName()).isEqualTo("Modbus Driver");
        assertThat(definition.getAds()).extracting(Ad::getId).contains("modbus.connection.type",
                "modbus.ip.address", "modbus.tcp.port", "modbus.unit.id", "modbus.response.timeout",
                "modbus.max.retries");
    }

    @Test
    public void declareConnectionTypeOptions() {
        final Ad connectionType = this.factory.getDefinition().getAds().stream()
                .filter(ad -> ad.getId().equals(ModbusDriverFactory.PROP_CONNECTION_TYPE)).findFirst().orElseThrow();

        assertThat(connectionType.getOptions()).extracting(option -> option.getValue())
                .containsExactly("TCP/IP", "TCP-RTU");
    }

    @Test
    public void exposeChannelDescriptor() {
        assertThat(this.factory.getChannelDescriptor()).isInstanceOf(ModbusChannelDescriptor.class);
        assertThat((List<?>) this.factory.getChannelDescriptor().getDescriptor()).hasSize(4);
    }

    @Test
    public void createModbusDriver() {
        final Driver driver = this.factory.create("modbus-1", Map.of());

        assertThat(driver).isInstanceOf(ModbusDriver.class);
        assertThat(((ModbusDriver) driver).getPid()).isEqualTo("modbus-1");
    }
}
