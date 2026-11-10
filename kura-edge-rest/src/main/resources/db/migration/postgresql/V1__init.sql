-- kura-edge 初始建表（PostgreSQL 版）：与 kura-edge-core 的 JPA 实体一一对应
-- （ComponentConfigurationEntity / WireGraphSnapshotEntity，Rules 05 §5）。
-- 与 h2/ 的差异只有列类型：H2 用 CLOB，PG 用 TEXT（PG 无 CLOB 类型，TEXT 即无长度上限的字符串）。
-- 两套脚本内容各自固定，已上线的库不要改（Flyway 校验 checksum）。

CREATE TABLE component_configuration (
    pid             VARCHAR(255)  NOT NULL PRIMARY KEY,
    factory_pid     VARCHAR(255)  NOT NULL,
    properties_json TEXT          NOT NULL
);

CREATE TABLE wire_graph_snapshot (
    service_pid VARCHAR(255) NOT NULL PRIMARY KEY,
    graph_json  TEXT         NOT NULL
);
