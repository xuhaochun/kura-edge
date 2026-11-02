/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.kura.wires.exception.WiresException;

/**
 * {@link JpaSnapshotStore} 的行为测试：基于 H2 内存库的
 * {@code @DataJpaTest} 切片，覆盖快照保存/加载/删除的正反路径。
 */
@DataJpaTest
public class JpaSnapshotStoreTest {

    private static final String SERVICE_PID = "org.eclipse.kura.wire.graph.WireGraphService";
    private static final String GRAPH_JSON = "{\"wireComponentConfigurations\":[],\"wireConfigurations\":[]}";

    @Autowired
    private WireGraphSnapshotRepository repository;

    private JpaSnapshotStore store;

    @BeforeEach
    public void setUp() {
        this.store = new JpaSnapshotStore(this.repository);
    }

    @Test
    public void successfulSaveAndLoadOfSnapshot() {
        this.store.save(SERVICE_PID, GRAPH_JSON);

        assertThat(this.store.load(SERVICE_PID)).contains(GRAPH_JSON);
    }

    @Test
    public void successfulSaveOverwritesExistingSnapshot() {
        givenSnapshotSaved(SERVICE_PID, GRAPH_JSON);

        this.store.save(SERVICE_PID, "{\"wireComponentConfigurations\":[],\"updated\":true}");

        assertThat(this.store.load(SERVICE_PID)).contains("{\"wireComponentConfigurations\":[],\"updated\":true}");
        assertThat(this.repository.count()).isEqualTo(1);
    }

    @Test
    public void successfulLoadReturnsEmptyWhenSnapshotIsAbsent() {
        assertThat(this.store.load("missing.pid")).isEmpty();
    }

    @Test
    public void successfulDeleteOfExistingSnapshot() {
        givenSnapshotSaved(SERVICE_PID, GRAPH_JSON);

        this.store.delete(SERVICE_PID);

        assertThat(this.store.load(SERVICE_PID)).isEmpty();
    }

    @Test
    public void successfulDeleteOfMissingSnapshotIsSilent() {
        this.store.delete("missing.pid");

        assertThat(this.repository.count()).isZero();
    }

    @Test
    public void rejectOperationsWhenServicePidIsInvalid() {
        assertThatThrownBy(() -> this.store.save(null, GRAPH_JSON)).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode())
                .isEqualTo(com.kura.wires.exception.ErrorCode.PERSISTENCE_ERROR);
    }

    private void givenSnapshotSaved(final String servicePid, final String graphJson) {
        this.store.save(servicePid, graphJson);
    }
}
