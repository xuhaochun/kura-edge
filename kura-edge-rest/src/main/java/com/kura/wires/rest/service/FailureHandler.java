/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import java.util.ArrayList;
import java.util.List;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;

/**
 * 多步写入的失败聚合器，对照上游
 * {@code org.eclipse.kura.rest.configuration.api.FailureHandler}：
 * 子步骤失败先记录、全部尝试完再聚合报错，避免半途抛异常留下不一致状态（Rules 07）。
 */
public class FailureHandler {

    private final List<String> failures = new ArrayList<>();

    /**
     * 执行一个可失败的子任务，失败时记录而不中断。
     *
     * @param name 子任务名（用于聚合报错定位）
     * @param task 子任务
     */
    public void runFallibleSubtask(final String name, final Runnable task) {
        try {
            task.run();
        } catch (final RuntimeException e) {
            this.failures.add(name + ": " + e.getMessage());
        }
    }

    /**
     * 记录一个失败（不执行子任务，如 updateGraph 这类需区分错误码的场景）。
     *
     * @param name 子任务名
     * @param cause 失败原因
     */
    public void processFailure(final String name, final RuntimeException cause) {
        this.failures.add(name + ": " + cause.getMessage());
    }

    /**
     * 存在失败时聚合抛出（500 引擎内部错误语义）。
     *
     * @throws WiresException 存在任一失败时
     */
    public void checkStatus() {
        if (!this.failures.isEmpty()) {
            throw new WiresException(ErrorCode.ENGINE_ERROR, "One or more subtasks failed: " + String.join("; ", this.failures));
        }
    }
}
