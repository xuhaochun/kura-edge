/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura 的 kura-modbus（https://github.com/eclipse-kura/kura-modbus，
 * EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.modbus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.kura.wires.engine.DriverFactory;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.AdOption;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.driver.ChannelDescriptor;
import com.kura.wires.model.driver.Driver;
import org.springframework.stereotype.Component;

/**
 * Modbus 驱动工厂：把 {@link ModbusDriver} 注册进驱动服务，让 Drivers and Assets 页面和 Wire Graph
 * 的 driver 下拉都能创建/选择 Modbus 实例。
 * <p>
 * 工厂 PID 与官方 Kura Modbus 驱动约定一致：{@code org.eclipse.kura.driver.modbus}。
 * </p>
 */
@Component
public final class ModbusDriverFactory implements DriverFactory {

    /** 驱动工厂 PID。 */
    public static final String FACTORY_PID = "org.eclipse.kura.driver.modbus";

    /** 连接类型键。 */
    public static final String PROP_CONNECTION_TYPE = "modbus.connection.type";

    /** 从站 IP 键。 */
    public static final String PROP_IP_ADDRESS = "modbus.ip.address";

    /** TCP 端口键。 */
    public static final String PROP_TCP_PORT = "modbus.tcp.port";

    /** unit id 键。 */
    public static final String PROP_UNIT_ID = "modbus.unit.id";

    /** 响应超时键。 */
    public static final String PROP_RESPONSE_TIMEOUT = "modbus.response.timeout";

    /** 重试次数键。 */
    public static final String PROP_MAX_RETRIES = "modbus.max.retries";

    private static final Ocd DEFINITION = buildDefinition();

    @Override
    public String factoryPid() {
        return FACTORY_PID;
    }

    @Override
    public Ocd getDefinition() {
        return DEFINITION;
    }

    @Override
    public ChannelDescriptor getChannelDescriptor() {
        return new ModbusChannelDescriptor();
    }

    @Override
    public Driver create(final String pid, final Map<String, Object> properties) {
        return new ModbusDriver(pid, properties);
    }

    private static Ocd buildDefinition() {
        final List<Ad> ads = new ArrayList<>();

        final Ad connectionType = new Ad(PROP_CONNECTION_TYPE, "Connection type", Scalar.STRING, true, "TCP/IP");
        connectionType.setDescription("TCP/IP uses standard Modbus TCP (MBAP header); TCP-RTU sends RTU frames"
                + " (with CRC-16) over a TCP connection");
        connectionType.setOptions(List.of(new AdOption("Modbus TCP", "TCP/IP"),
                new AdOption("RTU over TCP", "TCP-RTU")));
        ads.add(connectionType);

        final Ad ip = new Ad(PROP_IP_ADDRESS, "IP address", Scalar.STRING, true, "127.0.0.1");
        ip.setDescription("IPv4 address or host name of the Modbus slave/gateway");
        ads.add(ip);

        final Ad port = new Ad(PROP_TCP_PORT, "TCP port", Scalar.INTEGER, true, 502);
        port.setDescription("TCP port of the Modbus slave/gateway (502 is the Modbus standard port)");
        port.setMin(1.0d);
        port.setMax(65535.0d);
        ads.add(port);

        final Ad unitId = new Ad(PROP_UNIT_ID, "Unit id", Scalar.INTEGER, true, 1);
        unitId.setDescription("Modbus unit (slave) identifier");
        unitId.setMin(0.0d);
        unitId.setMax(255.0d);
        ads.add(unitId);

        final Ad timeout = new Ad(PROP_RESPONSE_TIMEOUT, "Response timeout (ms)", Scalar.INTEGER, true, 1000);
        timeout.setDescription("Socket connect and response timeout in milliseconds");
        timeout.setMin(1.0d);
        timeout.setMax(60000.0d);
        ads.add(timeout);

        final Ad retries = new Ad(PROP_MAX_RETRIES, "Max retries", Scalar.INTEGER, true, 2);
        retries.setDescription("Number of retries after a timeout or I/O failure (0 disables retries)");
        retries.setMin(0.0d);
        retries.setMax(10.0d);
        ads.add(retries);

        return new Ocd(FACTORY_PID, "Modbus Driver",
                "Modbus master connecting to a slave/gateway over Modbus TCP or RTU over TCP", List.copyOf(ads));
    }
}
