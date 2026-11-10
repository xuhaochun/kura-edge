# RFC 007：PostgreSQL 化 + 蓝绿零中断热更新 + 页面内升级入口

- 状态：已实现，待 Windows 实机冒烟
- 档位：L（数据库默认值变更 + 新增选主层 + 外壳状态机重写 + 前端新入口）
- 关联：RFC 005（Tauri 打包）、RFC 006（后端 jar 热更新）
- 上游参考：无（纯本项目工程决策，与 Kura 上游无关）

## 0. 要解决的三件事

1. **数据库换成现场那台 PostgreSQL**（`192.168.31.40:15432`，库 `kuraedge`，用户 `postgres`）。
   RFC 005 当初为了"装完即用、零外部依赖"选了文件模式 H2，代价是**无法多实例**：同一个
   `.mv.db` 不允许两个 JVM 同时打开。
2. **把 RFC 006 里"已知限制"那条补上**：06 的热更新是"停旧 → 起新"，页面黑屏 15~25 s。
   用户要求**零中断**，而零中断必须能两个 JVM 并存——这正是 H2 做不到、PG 做得到的事。
3. **页面上要能升级**：主界面 `Kura Edge` 右边加「升级」按钮，填一个网址就把后端 jar 换掉。

## 1. 数据库：PostgreSQL 成为默认，H2 降为可选 profile

### 1.1 决策

| 项 | 决定 | 理由 |
|---|---|---|
| 默认库 | `jdbc:postgresql://192.168.31.40:15432/kuraedge`，`postgres` / `iot123...` | 现场实际部署形态；设备只需**单向**访问数据库，与"上位机只能单向访问服务器"一致 |
| 覆盖方式 | `KURA_EDGE_DB_URL` / `_USER` / `_PASSWORD`，另有 `KURA_EDGE_DB_DRIVER` 不必改 | 沿用 RFC 005 的 `KURA_EDGE_*` 约定；投产后口令必须换成环境变量并轮换 |
| 离线/单机 | `--spring.profiles.active=h2` 走文件 H2，位置不变（`~/.kura-edge/data/kuraedge`） | 现场断网、开发机、CI 都能跑；此模式下蓝绿自动降级为就地重启 |
| 连接池 | `hikari.maximum-pool-size: 5`、`connection-timeout: 10000` | 蓝绿要求池里至少两条连接（一条被咨询锁独占）；连不上要尽快报错让外壳显示错误页 |

> 规则冲突说明：rules/05 §11 要求"密钥只进环境变量或未提交配置"。本次把内网口令写进了
> `application.yml` 的默认值——这是用户明确指令下的取舍（开箱即用优先），已在配置里就地注明
> "投产前必须用 `KURA_EDGE_DB_PASSWORD` 覆盖并轮换"。若要严格守规，把默认值改成空串、
> 由部署环境注入即可，代码无需改动。

### 1.2 Flyway 按 vendor 分目录

两套脚本只有列类型差异，改成 `spring.flyway.locations: classpath:db/migration/{vendor}`：

```
db/migration/h2/V1__init.sql         CLOB
db/migration/h2/V2__wire_records.sql CLOB
db/migration/postgresql/V1__init.sql         TEXT
db/migration/postgresql/V2__wire_records.sql TEXT
```

`h2/` 下的文件是**原样移动**（内容一字未改，checksum 不变），所以已经上过 V1/V2 的老 H2 库
照样通过校验；PG 侧是全新库，直接走 `postgresql/`。

### 1.3 两个必须修的兼容坑

1. **Flyway 10.10.0 不认识 PostgreSQL 18**（服务端 18.6）→ 启动即
   `Unsupported Database: PostgreSQL 18.6`。升到 **11.20.3**，并显式加
   `flyway-database-postgresql`（Flyway 10+ 把方言拆成独立模块）。
   注意：直接在本项目 `<properties>` 里改 `flyway.version` **覆盖不动** Boot BOM 里的版本
   （BOM 内部的 `${flyway.version}` 在 BOM 里就解析定死了），必须在依赖上显式写 `<version>`。
2. **`@Lob String` 在 PG 上映射成 `oid` 大对象**，`ddl-auto: validate` 要求列也是 oid，
   而迁移脚本给的是 `TEXT` → `Schema-validation: wrong column type ... found [text], but expecting [oid]`。
   试过的两条路与结论：
   - 实体改成 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`：PG 通过，但 **H2 上期望 `varchar(32600)`、
     实际是 CLOB，老库直接起不来** —— 否决（不能为了 PG 把已部署的老库弄坏）。
   - **`KuraPostgresDialect extends PostgreSQLDialect`，把 `SqlTypes.CLOB` 的列类型映射成 `text`**：
     PG 上 `@Lob` ↔ `TEXT` 对上，H2 仍走原生 CLOB，实体与两套脚本都不用动 —— **采用**。
     方言只在默认 profile 配置（`spring.jpa.database-platform`），`h2` profile 显式指回 `H2Dialect`。

## 2. 零中断：PG 咨询锁选主 + 蓝绿交接

### 2.1 核心约束

图执行（定时器 tick、Modbus 采集、信封投递）是**进程内**的：两个 JVM 同时跑同一张图 =
同一台设备被采两遍、同一份数据被上报两遍。所以"两个 JVM 并存"必须先解决"谁有权跑图"。

### 2.2 选主：PostgreSQL 会话级咨询锁

`kura-edge-core` 新增 `com.kura.wires.core.ha`：

| 类 | 职责 |
|---|---|
| `GraphLease` | 接口：`supported / tryAcquire / stillHeld / release` |
| `AdvisoryLockLease` | `pg_try_advisory_lock(20261110)`，锁必须独占一条连接（归还连接=放锁）；心跳 `SELECT 1` 失败即视为丢锁 |
| `AlwaysLeaderLease` | 单机/测试：永远有执行权，行为与改动前一致 |
| `GraphLeadership` | 选主协调者：ready 阶段抢锁 → 抢到发 `GraphLeadershipGrantedEvent`；抢不到当 standby 每 500 ms 重试；持锁期间心跳掉线发 `GraphLeadershipRevokedEvent` |
| `GraphLeadershipGrantedEvent` / `RevokedEvent` | record 事件，避免 `WireGraphEngine` ↔ `GraphLeadership` 循环依赖 |

`kura.bluegreen.enabled`：`auto`（默认，按数据源产品名判断，PostgreSQL 才启用）/ `on` / `off`。

**为什么只门控图就够了**：驱动/资产服务在 `@PostConstruct` 里只是把实例重建出来（`DriverServiceImpl.init`
→ `reloadAll()`、`AssetServiceImpl.init` → `reloadAll()`），并不主动采集——Modbus 的通道是**按需**读写的，
真正驱动它的是 `WireAsset` 组件的 tick。所以 standby 不启动图就没有任何设备 I/O，不会与 leader 抢设备。

### 2.3 引擎门控

`WireGraphEngine.restoreGraph()` 的触发从 `ApplicationReadyEvent` 改成
`GraphLeadershipGrantedEvent`；新增 `pauseGraph()` 监听 `RevokedEvent`（取消全部 tick，不销毁实例，
重新拿到锁后可复用）。单机模式下 `GraphLeadership` 在 ready 阶段就发授权事件，时序与以前完全一致。

### 2.4 交接时序（外壳驱动）

```
旧实例(leader, P1)                外壳                        新实例(standby, P2)
    │  ①POST /update 落 jar+pending │                              │
    │─────────────────────────────▶ │  ②读 pending.json            │
    │  蓝绿模式不退出，继续服务       │  ③用新 jar 起 JVM(P2)        │
    │                              │ ────────────────────────────▶ │ 抢不到锁 → 起 HTTP 不跑图
    │                              │  ④等 P2 HTTP 200             │
    │                              │  ⑤窗口导航到 P2（用户已在新版）│
    │  ⑥POST /retire                │                              │
    │◀───────────────────────────── │                              │
    │  停图(pauseGraph) → 放锁 → 退出 │  ⑦等旧进程退出               │ 0.5 s 内抢到锁
    │                              │  ⑧等 P2 role=leader          │ 恢复整图（同库快照）
    │                              │  ⑨写 current.json、归档、清理  │
```

- **页面**：全程只有一次"导航到 P2"，没有黑屏等待；
- **图执行**：中断 = 旧实例停图 → 新实例重建组件（实测亚秒到 1~2 s），而不是整机冷启 15~25 s；
- **不会重复执行**：任一时刻最多一个实例持有租约。

### 2.5 失败面

| 失败点 | 后果 | 处理 |
|---|---|---|
| standby 起不来 / 120 s 未就绪 | **旧实例毫发无损** | 杀掉 standby，写 `apply-result.json=rolled_back` |
| 旧实例不受理 `/retire` | 旧实例仍在跑图 | 强杀（图随之停），继续等新实例接管 |
| 移交后新实例未 `role=leader`（20 s） | 服务可能无图可跑 | 杀新实例 → 按旧指针重启旧 jar（唯一会真中断的兜底路径） |
| 写 `current.json` 失败 | 当前服务正常，下次冷启动回旧版 | 如实记 `applied` + error，不谎报 |

### 2.6 端点变化（rules/07 同步更新）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/system/version` | 新增 `role`（leader/standby）与 `blueGreen` 两个字段 |
| POST | `/api/v1/system/update` | 蓝绿模式下**不再自杀**；`sha256`/`version` 改为可留空 |
| POST | `/api/v1/system/retire` | 新增：停图 → 放锁 → 300 ms 后优雅退出；单机模式返回 409004 |

`sha256` / `version` 留空的补齐规则：`version` 从 URL 文件名推导（`kura-edge-<版本>.jar`），
推导不出来在下载之前就 400004；`sha256` 留空则按实际下载到的摘要认定并 `WARN`
（只靠 HTTPS/内网信任，因此界面上仍把摘要作为"建议填写"的高级项）。

## 3. 页面内升级入口

- 位置：左侧栏 `Kura Edge` 品牌行右侧的「升级」文字按钮（最显眼的运维动作）；
- 弹窗：**网址必填**，`sha256` / 版本号是可选项；打开时读 `/system/version` 显示当前版本与角色，
  单机模式额外提示"会中断 15~25 秒"；
- 提交后展示"已受理"，不自己刷新——由外壳在合适时机导航（蓝绿几乎无感）。

## 4. 兼容性与数据

- **老 H2 部署**：`h2` profile + 原迁移脚本 + 原实体映射，行为与 RFC 006 完全一致（就地重启）。
  H2 → PG 不做自动迁移（schema 一致，但需要人工导数据，属现场运维动作）。
- **PG 已是共享库**：两个 JVM 会同时跑 Flyway → 由 Flyway 的库级锁串行化，第二个实例等待即可。
- **回滚边界**（沿用 RFC 006）：只回滚到 `previous`；带 schema 迁移的版本不可回滚。

## 5. DoD

- [x] `mvn verify`：core（含 `ha` 包）与 rest 全绿，覆盖率门槛未破
- [x] PG 真实启动验证：Flyway 建表 + `ddl-auto: validate` 通过 + 图恢复
- [x] H2 profile 回归：同一 jar 加 `--spring.profiles.active=h2` 能起
- [x] `desktop/update-core`：`cargo test` 全绿
- [x] 外壳：`cargo xwin check --target x86_64-pc-windows-msvc` 通过
- [x] 前端：`npm run lint && npm run type-check && npm run test:unit && npm run build`
- [x] 文档：本 RFC、`desktop/README.md`、`rules/07`、`CLAUDE.md`
- [ ] Windows 实机冒烟（构建机是 Linux，无法跑 exe）：正常升级 / standby 起不来 / 移交失败回滚三条路径

## 6. 实现与初稿的差异

1. 初稿打算用"数据库里一行 flag"选主，改为**会话级咨询锁**：进程被 kill/崩溃/断电时锁随连接
   自动释放，不需要任何人工清理，也不会留下需要心跳超时判定的死锁。
2. 初稿打算让外壳用 `kill` 结束旧进程，改为 `POST /retire` 优雅移交：`kill` 是 `TerminateProcess`，
   会跳过 `pauseGraph()`，存在"旧实例的 tick 还在跑、新实例已经接上"的重复执行窗口。
3. 初稿打算让后端在蓝绿模式自己也延迟退出，改为**后端只落包等移交**：退出时机必须由外壳掌握
   （它才知道新实例是否真的起来了）。
4. `sha256` 从"必填"改为"可留空"：用户要的是"填一个网址就能升级"，摘要改为后端补齐 + 前端提示。
5. `@Lob` 的 PG 适配最终用方言子类而不是改实体：改实体会让已部署的 H2 老库 `validate` 失败。
