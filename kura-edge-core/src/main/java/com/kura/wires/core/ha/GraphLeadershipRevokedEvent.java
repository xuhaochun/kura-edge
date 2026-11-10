/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

/**
 * 本进程失去图执行权的事件：{@code WireGraphEngine} 收到后必须**立即停图**。
 * <p>
 * 触发场景是数据库连接断了（会话级咨询锁已被 PostgreSQL 释放）。此时其它实例可能已经抢到锁开始跑图，
 * 继续 tick 就是两边重复执行——宁可停图等重新抢到锁再恢复。
 */
public record GraphLeadershipRevokedEvent() {
}
