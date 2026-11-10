/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

import java.time.Instant;

/**
 * 一条组件运行日志行：由 {@link ComponentLogBuffer} 按组件 PID 保存，
 * 经 REST/SSE 推送给前端「日志详情」面板。
 * <p>
 * {@code seq} 全局单调递增（跨组件、跨图重载都不重置）：前端以最后一次收到的
 * seq 作为增量起点，避免轮询/推送间隙漏行；后端重启后 seq 从 0 重新开始，
 * 消费方据此判断需要重新对齐。
 * </p>
 *
 * @param seq 全局单调序号
 * @param timestamp 产生时刻（UTC）
 * @param text 日志文本（与同名组件写 SLF4J 的内容一致）
 */
public record ComponentLogLine(long seq, Instant timestamp, String text) {
}
