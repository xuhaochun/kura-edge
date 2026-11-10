/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

/**
 * 图执行权的租约：谁持有它，谁才有资格跑线图（定时器 tick、驱动轮询、信封投递）。
 * <p>
 * 蓝绿热更新时同一个 PostgreSQL 上会有两个 JVM：旧版本继续服务，新版本作为 standby 已就绪但
 * **不跑图**，否则定时器与 Modbus 采集会被重复执行两遍（对现场设备是灾难）。实现见
 * {@link AdvisoryLockLease}（PostgreSQL 咨询锁）与 {@link AlwaysLeaderLease}（单机，无分布式能力）。
 */
public interface GraphLease {

    /**
     * 是否具备跨进程互斥能力（只有 PostgreSQL 咨询锁才是 true）。
     * <p>
     * false 表示"单机模式"：热更新只能就地停旧起新，做不到零中断。
     *
     * @return 是否支持多实例互斥
     */
    boolean supported();

    /**
     * 非阻塞地尝试取得执行权。
     *
     * @return true 表示本进程现在是唯一的图执行者
     */
    boolean tryAcquire();

    /**
     * 当前是否仍持有执行权（含心跳校验，用于发现数据库连接已断、锁早已丢失）。
     *
     * @return 是否仍持有
     */
    boolean stillHeld();

    /**
     * 主动交出执行权（热更新移交前调用，让新实例立刻能接上）。
     */
    void release();
}
