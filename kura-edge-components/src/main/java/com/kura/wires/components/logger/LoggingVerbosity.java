/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

/**
 * Logger 组件的日志详细程度，对照上游
 * {@code org.eclipse.kura.internal.wire.logger.LoggingVerbosity}。
 */
enum LoggingVerbosity {

    /** 只记录信封摘要 */
    QUIET,
    /** 逐条打印记录内容 */
    VERBOSE
}
