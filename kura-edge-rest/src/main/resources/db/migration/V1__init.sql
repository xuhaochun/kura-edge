-- kura-edge 初始建表脚本：与 kura-edge-core 的 JPA 实体一一对应
-- （ComponentConfigurationEntity / WireGraphSnapshotEntity，Rules 05 §5）。

CREATE TABLE component_configuration (
    pid             VARCHAR(255)  NOT NULL PRIMARY KEY,
    factory_pid     VARCHAR(255)  NOT NULL,
    properties_json CLOB          NOT NULL
);

CREATE TABLE wire_graph_snapshot (
    service_pid VARCHAR(255) NOT NULL PRIMARY KEY,
    graph_json  CLOB         NOT NULL
);
