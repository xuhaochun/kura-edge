/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.driverhost.simulated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Scalar;
import com.kura.wires.model.driver.ChannelDescriptor;

/**
 * 模拟驱动的通道描述符：每个通道一个参数 {@code simulated.register}（寄存器名）。
 * <p>
 * 驱动自有参数 <b>不带</b> {@code +} 前缀（{@code +} 段是资产基座参数的判据），
 * 因此属性键形如 {@code myChannel#simulated.register}。
 * </p>
 */
public final class SimulatedChannelDescriptor implements ChannelDescriptor {

    /** 通道参数：寄存器名 */
    public static final String REGISTER_PARAM = "simulated.register";

    /** 通道参数默认值 */
    public static final String DEFAULT_REGISTER = "register-1";

    private static final List<Ad> DESCRIPTOR = build();

    @Override
    public Object getDescriptor() {
        return DESCRIPTOR;
    }

    private static List<Ad> build() {
        final Ad register = new Ad(REGISTER_PARAM, "simulated.register", Scalar.STRING, true, DEFAULT_REGISTER);
        register.setDescription("Name of the simulated register bound to this channel");
        final List<Ad> ads = new ArrayList<>();
        ads.add(register);
        return Collections.unmodifiableList(ads);
    }
}
