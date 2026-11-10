/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

/**
 * 单机模式的租约：永远认为自己有执行权。
 * <p>
 * 用在文件 H2 / 测试 / 显式关闭蓝绿（{@code kura.bluegreen.enabled=false}）的场景：此时不可能有第二个
 * JVM 同时跑同一份数据，行为与没有蓝绿之前完全一致（启动即恢复图）。
 */
public class AlwaysLeaderLease implements GraphLease {

    private boolean held = true;

    @Override
    public boolean supported() {
        return false;
    }

    @Override
    public boolean tryAcquire() {
        this.held = true;
        return true;
    }

    @Override
    public boolean stillHeld() {
        return this.held;
    }

    @Override
    public void release() {
        this.held = false;
    }
}
