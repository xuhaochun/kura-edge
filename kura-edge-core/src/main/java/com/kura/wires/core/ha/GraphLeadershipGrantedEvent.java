/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

/**
 * 本进程取得图执行权的事件：{@code WireGraphEngine} 收到后才会恢复/接管线图。
 *
 * @param blueGreen 是否处于蓝绿模式（true 表示跨进程互斥可用，热更新可以零中断）
 */
public record GraphLeadershipGrantedEvent(boolean blueGreen) {
}
