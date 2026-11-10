/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.core.snapshot.SnapshotStore;

/**
 * 内存版快照存储：同一份图 JSON 可在多个引擎实例之间共享，
 * 用于模拟「进程重启后从持久化快照恢复整图」（对照 {@code JpaSnapshotStore}）。
 */
public final class InMemorySnapshotStore implements SnapshotStore {

    private final Map<String, String> snapshots = new LinkedHashMap<>();

    @Override
    public void save(final String servicePid, final String graphJson) {
        this.snapshots.put(servicePid, graphJson);
    }

    @Override
    public Optional<String> load(final String servicePid) {
        return Optional.ofNullable(this.snapshots.get(servicePid));
    }

    @Override
    public void delete(final String servicePid) {
        this.snapshots.remove(servicePid);
    }
}
