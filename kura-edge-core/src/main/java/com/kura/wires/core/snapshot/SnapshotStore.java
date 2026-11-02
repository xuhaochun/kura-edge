/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import java.util.Optional;

/**
 * 整图快照存取 SPI：图引擎与快照持久化之间的边界，便于测试替换内存实现
 * （对照上游 ConfigurationAdmin 快照中 {@code WireGraph} 属性的读写）。
 */
public interface SnapshotStore {

    /**
     * 保存（或整体覆盖）指定服务 PID 的整图 JSON。
     *
     * @param servicePid 服务 PID
     * @param graphJson 整图 JSON
     * @throws com.kura.wires.exception.WiresException 持久化失败时（PERSISTENCE_ERROR）
     */
    void save(String servicePid, String graphJson);

    /**
     * 读取指定服务 PID 的整图 JSON。
     *
     * @param servicePid 服务 PID
     * @return 整图 JSON；不存在时为空
     * @throws com.kura.wires.exception.WiresException 持久化失败时（PERSISTENCE_ERROR）
     */
    Optional<String> load(String servicePid);

    /**
     * 删除指定服务 PID 的快照（不存在时静默返回）。
     *
     * @param servicePid 服务 PID
     * @throws com.kura.wires.exception.WiresException 持久化失败时（PERSISTENCE_ERROR）
     */
    void delete(String servicePid);
}
