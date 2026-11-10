/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

/**
 * 进程退出钩子（RFC 006 §2.3）：热更新受理后延迟调用，让 JVM 走正常的 Spring 关闭流程
 * （Hikari 归还连接、H2 刷盘）再结束进程，外壳据此换 jar 并重启。
 * <p>
 * 抽成接口的原因：{@link BackendUpdateService} 的单测不能真的把测试 JVM 杀掉；
 * 生产实现见 {@link SpringApplicationExitHook}。
 * </p>
 */
public interface BackendExitHook {

    /**
     * 触发进程退出。调用后不再返回。
     */
    void exit();
}
