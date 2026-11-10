# RFC 006 — 后端 jar 热更新：版本化 jar + 外壳监督重启（不重启 exe）

- 状态：**已实现（2026-11-10）→ 待 Windows 实机冒烟**（构建机是 Linux，见 §7）
- 提出：2026-11-10
- 档位：**L**（新增 Rust 子 crate + 重写 Tauri 外壳启动链路 + 新增 rest 端点与文件协议）
- 相关规则：rules/04（分层；rest 唯一启动模块）、rules/05（安全/密钥/边界）、rules/06（Gherkin + 覆盖率）、
  rules/07（REST/错误码）、`CLAUDE.md`「硬约束清单」。
- 前序：RFC 005（桌面化打包）把「自动更新」列为不包含项，本 RFC 是其续篇。

## 0. 结果意象（用户视角，先于代码）

**现状**：换后端只能重装 `kura-edge_1.0.3_x64-setup.exe`，或手工替换安装目录里的
`resources/app/kura-edge.jar`——而运行中的 JVM 持有该文件句柄，Windows 上覆盖必然失败；
即使替换成功也要重启 exe。

**之后**：

- 运维在服务器上放好新 jar，对设备发一次 HTTP 请求（带 `wires.admin` 凭据）：

  ```
  POST /api/v1/system/update
  {"url":"https://server/kura-edge-1.0.4.jar","sha256":"<64 位十六进制>","version":"1.0.4"}
  ```

  响应 `202 {code:0,data:{version,jar,sha256}}`。约 1.5 s 后**后端进程优雅退出**，外壳窗口
  原地显示「正在更新后端…」，不需要关掉、也不会闪退。
- 外壳装好新 jar → 用同一端口、同一口令重启 JVM → 探测到 `HTTP 200` → 窗口自动刷新，
  直接就是新版本的前端页面（前端产物打在 jar 的 `static/` 里）。**exe 从未重启**。
- 新版本起不来（配置写坏、jar 损坏、端口被占）：外壳自动回滚到上一版并重启它，页面照常可用；
  `apply-result.json` 与 `backend.log` 里写明原因。设备不会变砖。
- `GET /api/v1/system/version` 返回当前运行的 jar 版本与路径，运维/前端可校验。
- 断网、只改数据不换 jar 的场景完全不受影响：没有 `staging/pending.json` 时外壳什么都不做。

**不包含**（留给后续）：

- exe/内嵌 JRE 的自动更新（Tauri updater 通道，属 RFC 007+ 的另一条线；JRE 被运行中的
  `javaw.exe` 占用，不能热换）。
- 灰度/分批、更新清单服务器、Ed25519 签名（本期只做 sha256；签名需要的公钥分发与密钥管理先不做）。
- 单实例锁（见 §6 已知限制）。
- 蓝绿双进程（真·零停机；H2 `AUTO_SERVER` 下首进程退出会切断其他进程连接，不可行）。
- 前端 UI（本期不加「检查更新」按钮，运维用 HTTP 调；外壳负责刷新页面）。

## 1. 决策记录

| # | 岔路 | 选定 | 理由 |
|---|---|---|---|
| D1 | 更新手法 | **进程级热重启**：换 jar、重拉 JVM，外壳不动 | 后端本来就是 `javaw -jar` 独立子进程（RFC 005），"重启后端≠重启 exe"是现有架构白送的能力 |
| D2 | 谁触发重启 | **后端自己优雅退出**，外壳只负责"等它退出→装→起" | 免掉 Windows 上无法给子进程发 SIGTERM 的问题（`Child::kill` = `TerminateProcess`，会掐掉 H2 刷盘）；Spring 的 graceful shutdown 只有收到正常停机信号才生效 |
| D3 | 外壳与后端怎么通信 | **文件即协议**（`staging/pending.json` + `app/current.json` + `apply-result.json`） | WebView 加载的是 `http://127.0.0.1:<port>` 远程页面，Tauri v2 默认不给远程页面 IPC；开 `remote.urls` 能力等于把 `core:default` 暴露给后端页面。文件协议零权限、两侧可独立测试、失败可见 |
| D4 | jar 存放 | **版本化文件名**（`app/kura-edge-<ver>.jar`）+ `current.json` 指针 | 运行中的 JVM 持有 jar 句柄，Windows 上不能覆盖/删除；版本化命名让"新文件"与"旧文件"共存，回滚只是改指针 |
| D5 | 更新来源 | **后端按 URL 拉取**（`java.net.http.HttpClient`，不引新依赖） | 上位机只能单向访问服务器（设备→服务器），运维无法在设备上放文件；后端有网络与认证，外壳不该自己实现下载 |
| D6 | 目录 | `%LOCALAPPDATA%\kura-edge\{app,staging,logs}`，**不写安装目录** | `installMode=currentUser` 目前可写，但换 `perMachine`（Program Files）就废；且 NSIS 卸载会清安装目录，更新产物不该被卸载动作牵连 |
| D7 | 端口 | **重启沿用同一端口**；bind 失败重试 3 次，换新空闲端口并让窗口导航到新 URL | 端口不变时页面 URL 不变，用户无感；150s 竞态窗口用重试兜住 |
| D8 | 口令 | **重启沿用同一口令**（外壳持有） | 前端是 HTTP Basic 每请求带凭据（`http.ts`），不是 session cookie，换 JVM 后凭据照旧有效，无需重登 |
| D9 | 校验 | sha256（后端下载后算一次、外壳安装前再算一次）+ `PK\x03\x04` 头 + 大小上限 | 后端算保证"下载完整"，外壳算保证"读到的就是要装的"（信任边界在安装器身上） |
| D10 | 回滚边界 | 只回滚到 `previous`（上一台已成功启动的 jar）；启动失败**连续 1 次**即回滚，回滚后不再重试 | 防抖动。DB schema 只前滚，故 RFC 规定：**跨 schema 迁移的版本不可回滚**，见 §5 |

## 2. 技术方案

### 2.1 目录与文件协议

```
%LOCALAPPDATA%\kura-edge\
├─ app\                                  KURA_EDGE_HOME/app —— 外壳独占写
│   ├─ current.json                      ← 唯一真相：启动用哪个 jar
│   ├─ kura-edge-1.0.3.jar               ← 首次启动从安装目录复制来的基线
│   ├─ kura-edge-1.0.4.jar               ← 热更装进来的
│   ├─ bundled.txt                       ← 安装目录基线 jar 的身份（版本/字节数/mtime）
│   └─ apply-result.json                 ← 最近一次应用结果（运维排障用）
├─ staging\                              KURA_EDGE_UPDATE_DIR —— 后端独占写
│   ├─ kura-edge-1.0.4.jar
│   ├─ pending.json                      ← 后端"请求外壳应用"的唯一信号
│   ├─ pending.applied-1731234567.json   ← 处理完后的归档（tag ∈ applied/rejected/failed/rolled_back）
│   └─ kura-edge-1.0.4.jar.part           ← 下载中（不完整，外壳不认）
└─ logs\backend.log
```

**单一写者原则**：`app\` 只有外壳写，`staging\` 只有后端写；两侧都只读对方的目录。

`current.json`：

```json
{
  "protocol": 1,
  "version": "1.0.4",
  "jar": "C:\\Users\\x\\AppData\\Local\\kura-edge\\app\\kura-edge-1.0.4.jar",
  "sha256": "…64 hex…",
  "appliedAt": "2026-11-10T03:06:00Z",
  "previous": { "version": "1.0.3", "jar": "…", "sha256": "…" }
}
```

`jar` 存绝对路径：安装目录可能在 C: 或 D:，基线 jar 就住在安装目录里。

`staging\pending.json`：

```json
{ "protocol": 1, "version": "1.0.4", "jar": "kura-edge-1.0.4.jar",
  "sha256": "…64 hex…", "size": 60440700, "requestedAt": "2026-11-10T03:06:00Z" }
```

`jar` 是相对 `staging\` 的文件名，正则白名单 `^kura-edge-[0-9A-Za-z._-]{1,64}\.jar$`（防路径穿越）。

`app\apply-result.json`：

```json
{ "protocol": 1, "status": "applied|rolled_back|rejected", "version": "1.0.4",
  "error": null, "at": "2026-11-10T03:06:40Z" }
```

### 2.2 启动时选 jar（外壳）

```
候选 = [current.jar, current.previous.jar, 安装目录基线 resources/app/kura-edge.jar]
逐个校验：文件存在 → 能读出 PK\x03\x04 → 大小 ≤ 1 GiB →（有记录时）sha256 匹配
第一个通过的即启动；current.json 缺失/损坏 → 用基线并在 app\ 落地基线副本 + 写 current.json
全不通过 → 内置错误页（列出每个候选的失败原因）
```

基线落地后 `current.json.version` 取 exe 版本（`app.package_info().version`），因为安装包里的 jar
与 exe 是同一版构建出来的（`build-desktop.sh` 同一次流水线产出）。

**基线身份识别（实现期补充，不在初稿里）**：`app\bundled.txt` 记录安装目录基线 jar 的
`<exe 版本>\t<字节数>\t<mtime 秒>`。启动时若它与当前文件不一致，说明**安装器换过基线**（exe 升级 / 修复安装），
于是重新落地基线并把旧版降为 `previous`——否则升级 exe 之后外壳会一直跑 `current.json` 里记着的旧 jar
（"装了新版没生效"）。只比三项便宜属性，不哈希 60 MB 文件。

### 2.3 应用时序（外壳 watcher，每 2 s 轮询一次 staging\pending.json）

```
1  无 pending.json / 已处理过同一文件（mtime+len 未变）        → 什么也不做
2  解析 + 校验（protocol、version、jar 白名单、文件存在、PK 头、size 一致、sha256 一致）
   失败 → 写 apply-result{rejected} → pending.json 改名 pending.rejected-<ts>.json
        → 若后端已退出则立刻重启旧 jar（不能留死设备）→ 结束
3  current.version==pending.version 且 sha256 相同               → 幂等，标记 done，结束
4  窗口切到"正在更新"提示（eval 注入遮罩，失败忽略）
5  等后端退出：轮询 child.try_wait() 最多 15 s；仍活着 → kill() + wait()（超时兜底 10 s）
6  原子安装：staging\<jar>  →rename→  app\<jar>（跨卷时退化为 copy+fsync+rename）
7  写 current.json（previous = 旧的 current，原子写 tmp+rename）
8  启动新 jar（同 port 同 password）；bind 失败换端口重试，最多 3 次
9  探测 HTTP 200，上限 120 s
   成功 → apply-result{applied} → 删 pending.json → 窗口 navigate 回后端首页（带 ?v=<version> 强制刷新）
   失败 → apply-result{rolled_back, error} → 恢复 current.json 到 previous → 启动旧 jar
          → 仍起不来 → 内置错误页 + 日志
10 单次更新内不再重试；下次运维重新发请求
```

### 2.4 后端侧（`kura-edge-rest`，只新增文件）

- `SystemUpdateController`（`/api/v1/system`，全部 `@PreAuthorize("hasRole('wires.admin')")`，
  `anyRequest().authenticated()` 已覆盖，`SecurityConfig` 不需要改）：
  - `GET /version` → `{version, jar, sha256, source}`（读 `app/current.json`；非桌面运行返回 `source:"maven"`）
  - `POST /update` → 202 `{version,jar,sha256}`；下载/校验失败 → `500004`（见 §3 错误码）
- `BackendUpdateService`：下载（`.part` → fsync → rename）→ 算 sha256 → 校验 → 写 `pending.json`
  （tmp+rename）→ 延迟 1.5 s 后 `SpringApplication.exit(ctx)` + `System.exit`。
- `ArtifactFetcher`（接口）+ `HttpArtifactFetcher`（`java.net.http.HttpClient`，只允许 http/https，
  跟随重定向，连接 10 s / 总 300 s，上限 512 MiB，边下边算 digest）。
- 安全约束（本端点带鉴权+落盘能力，按 S2 做威胁建模）：
  - 只允许 `http`/`https`；`file:`/`jar:` 一律拒（否则等于任意文件读）。
  - 目标文件名由 `version` 经白名单正则生成，绝不取 URL 里的路径（防路径穿越）。
  - 大小上限 + `Content-Length` 预检；超限即中止并删 `.part`。
  - 环境变量缺失（`KURA_EDGE_UPDATE_DIR` / `KURA_EDGE_HOME`）→ 直接 `409004`：
    "当前不是桌面运行时，无法热更新"（`mvn spring-boot:run` 下不该假装能更新）。
  - 并发两次更新请求 → `AtomicBoolean` 串行化，第二次 `409005`。

### 2.5 外壳（`desktop/src-tauri`）

`main.rs` 拆成：`paths.rs`（目录解析、`plain_path`）、`backend.rs`（启动/停机/健康探测）、
`update.rs`（watcher + §2.3 状态机）、`log.rs`。新增 `update-core` 纯逻辑 crate
（`desktop/update-core/`，依赖仅 serde/serde_json/sha2），把"解析、校验、原子写、决策"
全部下沉过去，于是这部分能在 Linux 上用 `cargo test` 真跑（tauri 本体在无 webkit2gtk-dev
的机器上编不了，这是把它拆出去的直接原因）。

## 3. 错误码（rules/07：`kura-edge-api` 的 `ErrorCode`）

| 码 | HTTP | 含义 |
|---|---|---|
| `500004` | 500 | 更新包下载失败（`UPDATE_DOWNLOAD_FAILED`） |
| `409004` | 409 | 非桌面运行时，不能热更新（`UPDATE_UNSUPPORTED`） |
| `409005` | 409 | 已有更新在进行（`UPDATE_IN_PROGRESS`） |
| `400004` | 400 | 更新包非法（版本号/URL/sha256 不合规，`UPDATE_REQUEST_INVALID`） |

## 4. 测试

| 层 | 用例（Gherkin 步骤方法） |
|---|---|
| Rust `update-core` | 版本号/文件名白名单正反；`pending.json` 解析正反（缺字段、协议不符、路径穿越）；sha256 校验正反；原子写后读回；决策矩阵（apply / skip / reject×4） |
| Java 服务 | 下载成功落 `.part`→正式名；sha256 不匹配拒并删文件；URL scheme 非 http 拒；超限中止；env 缺失拒；并发第二次被拒；成功写 pending.json 且安排退出 |
| Java 控制器 | 401 / 403 / 200 / 202 / 400 / 500 映射 |
| 冒烟（手工，Windows） | 见 §7 |

覆盖率：rest ≥ 70%（模块门槛，JaCoCo 强制）。

## 5. 兼容性与回滚约束

- 快照 JSON / REST 语义**零变化**（只新增 `/api/v1/system/**`）。
- DB：Flyway 只前滚，`ddl-auto: validate`。故：**带 schema 迁移的版本不可回滚**——回滚后的旧 jar
  会因 validate 失败起不来，外壳会报错误页。运维须把"跨迁移版本"当作不可回滚发布；本期不做
  自动备份（列已知限制）。
- 前后端同包：换 jar 即换前端，无版本错配。

## 6. 已知限制

1. **无单实例锁**：双开 exe 仍可能两个外壳都盯着 `staging\`（apply 因"版本已就绪则跳过"而幂等，
   但两个 JVM 抢同一 H2 文件仍是既有风险）。单实例锁用 `tauri-plugin-single-instance` 另开一张单处理。
2. 更新期间图执行中断（JVM 退出）15~25 s，属方案 A 的固有代价；要"零中断"只能上蓝绿 + PostgreSQL。
3. 只支持"整 jar"粒度；单驱动/单组件热插拔不在本期（见先前调研的路线 B）。
4. 无签名校验（只有 sha256 + HTTPS），更新源本身被攻破时无法识别。
5. 回滚不备份 H2 数据文件。

## 7. DoD

- [x] `cd desktop/update-core && cargo test` 全绿（37 项：清单解析、版本/文件名白名单、sha256、
      原子写、决策矩阵、启动候选回退、旧 jar 清理）
- [x] `cargo xwin check --target x86_64-pc-windows-msvc`（`src-tauri`）通过
      （需 `PATH=~/tools/tauri-cross/bin`、`LD_LIBRARY_PATH=~/tools/tauri-cross/llvm/usr/lib/x86_64-linux-gnu`、
      `XWIN_CACHE_DIR=~/.cache/cargo-xwin`，与 `build-desktop.sh` 一致）
- [x] `mvn -pl kura-edge-rest -am test` 全绿（含既有 ArchUnit；新增 27 项：
      控制器 9、更新服务 9、下载器 9）
- [x] `mvn -pl kura-edge-rest -am verify` 全绿（JaCoCo 覆盖率门槛未降）
- [ ] **Windows 实机冒烟（未做：构建机是 Linux，只能交叉编译）**：① 正常更新成功并刷新
      ② sha256 不符被拒且设备不断线 ③ 新 jar 起不来自动回滚 ④ 非桌面模式（`java -jar`）
      调 `/update` 返回 409004 —— 步骤见 `desktop/README.md`「后端热更新」与「Windows 侧冒烟清单」7~9
- [x] `desktop/README.md` 补「热更新」一节（含目录/文件协议、回滚边界、手工验证）；
      `CLAUDE.md` 工程结构表更新

### 实现与初稿的差异

1. `app\bundled.txt`（基线身份）与 `pending.<tag>-<ts>.json`（归档命名）是初稿没写的，见 §2.1/§2.2。
2. `install()` 在 `update-core` 里额外校验"目标必须在 `app\` 内"，防上层拼错路径把 jar 写到别处。
3. 后端侧大小上限用 512 MiB（外壳侧 1 GiB）：后端要真的把文件下下来，限额更保守。
4. `apply-result.json` 的 `status` 在"幂等跳过"时写 `applied`（而不是新增 `skipped`），
   保持与外壳共享的枚举只有三个值。
5. `desktop/scripts/build-desktop.sh` 新增 `--jar-only` 模式（只出热更新 jar，不跑 JRE/NSIS）与
   **四处版本号一致性校验**（`tauri.conf.json` / `package.json` / `src-tauri/Cargo.toml` /
   `update-core/Cargo.toml`；不一致就 fail-fast，因为外壳会以 `version_conflict` 拒装）；
   `dist/kura-edge-<版本>.jar` + `.sha256` 升为正式产物（正好就是 `/update` 要的两样），
   末尾打印可直接粘贴的 `curl`；打包前 `rm -rf resources/app` 重建，避免历史子模块残留的 jar
   被打进安装包。
