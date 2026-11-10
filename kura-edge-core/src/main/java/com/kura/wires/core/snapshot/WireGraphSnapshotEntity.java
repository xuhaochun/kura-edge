/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.snapshot;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 整图快照持久化实体：对照上游「ConfigurationAdmin snapshot + WireGraph 字符串属性」，
 * 以服务 PID（固定 {@code org.eclipse.kura.wire.graph.WireGraphService}）为主键，
 * 图 JSON 与上游快照中的 {@code WireGraph} 属性内容完全一致。
 */
@Entity
@Table(name = "wire_graph_snapshot")
public class WireGraphSnapshotEntity {

    /** 快照所属服务 PID（主键） */
    @Id
    @Column(name = "service_pid", nullable = false, length = 255)
    private String servicePid;

    /**
     * 整图 JSON（字段名与上游快照一致）。
     * <p>
     * {@code @Lob} 在 PG 上默认落成 {@code oid} 大对象，这里靠 {@code KuraPostgresDialect} 映射成
     * {@code text}；H2 仍是 CLOB（RFC 007）。
     */
    @Lob
    @Column(name = "graph_json", nullable = false)
    private String graphJson;

    /** JPA 规范要求的无参构造器。 */
    protected WireGraphSnapshotEntity() {
        // JPA 反射用
    }

    /**
     * 创建实体。
     *
     * @param servicePid 服务 PID
     * @param graphJson 整图 JSON
     */
    public WireGraphSnapshotEntity(final String servicePid, final String graphJson) {
        this.servicePid = servicePid;
        this.graphJson = graphJson;
    }

    public String getServicePid() {
        return this.servicePid;
    }

    public String getGraphJson() {
        return this.graphJson;
    }

    public void setGraphJson(final String graphJson) {
        this.graphJson = graphJson;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WireGraphSnapshotEntity)) {
            return false;
        }
        return Objects.equals(this.servicePid, ((WireGraphSnapshotEntity) obj).servicePid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.servicePid);
    }
}
