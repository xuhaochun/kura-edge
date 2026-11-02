/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;

/**
 * {@link SnapshotStore} 的 JPA 实现（对照上游 ConfigurationAdmin 快照存储）。
 */
@Service
@Transactional
public class JpaSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(JpaSnapshotStore.class);

    private final WireGraphSnapshotRepository repository;

    /**
     * 创建快照存储。
     *
     * @param repository 快照仓库
     */
    public JpaSnapshotStore(final WireGraphSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(final String servicePid, final String graphJson) {
        try {
            final WireGraphSnapshotEntity entity = this.repository.findById(servicePid)
                    .orElseGet(() -> new WireGraphSnapshotEntity(servicePid, graphJson));
            entity.setGraphJson(graphJson);
            this.repository.save(entity);
        } catch (final RuntimeException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Failed to save wire graph snapshot " + servicePid, e);
        }
        log.info("Saved wire graph snapshot {}", servicePid);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> load(final String servicePid) {
        try {
            return this.repository.findById(servicePid).map(WireGraphSnapshotEntity::getGraphJson);
        } catch (final RuntimeException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Failed to load wire graph snapshot " + servicePid, e);
        }
    }

    @Override
    public void delete(final String servicePid) {
        try {
            this.repository.deleteById(servicePid);
        } catch (final RuntimeException e) {
            throw new WiresException(ErrorCode.PERSISTENCE_ERROR, "Failed to delete wire graph snapshot " + servicePid, e);
        }
        log.info("Deleted wire graph snapshot {}", servicePid);
    }
}
