/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.registry;

import com.kura.wires.model.WireComponent;

/**
 * 组件实例变更监听器：注册表创建/销毁实例时回调，供图引擎的投递层感知
 * 运行时拓扑变化（对照上游 WireAdmin 在 Wire/Producer/Consumer 服务
 * 注册与注销时的跟踪回调，如 {@code WireComponentTrackerCustomizer}）。
 * <p>
 * 回调在注册表写锁外同步执行，实现方必须快速返回，不得回调注册表的
 * 创建/销毁方法（会造成重入死锁）。
 * </p>
 */
public interface WireComponentRegistryListener {

    /**
     * 组件实例创建并纳入跟踪后回调。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 组件工厂 PID
     * @param instance 创建的组件实例
     */
    void onInstanceCreated(String pid, String factoryPid, WireComponent instance);

    /**
     * 组件实例销毁并移出跟踪后回调。
     *
     * @param pid 组件实例 PID
     * @param factoryPid 组件工厂 PID
     */
    void onInstanceDestroyed(String pid, String factoryPid);
}
