# kura-edge 桌面版（Tauri 2 外壳）

把 kura-edge 打成 Windows 安装包：Tauri 负责窗口，内嵌的 Temurin 17 JRE 负责跑 Spring Boot，
前端由 Spring Boot 同源托管。用户装完双击图标即可用，**目标机不需要预装 Java**。

> 设计依据：`change/rfc/005-desktop-tauri-packaging.md`

## 产物

```
desktop/dist/kura-edge_<版本>_x64-setup.exe     # NSIS 安装包（installMode=currentUser，约 79 MiB）
desktop/dist/kura-edge-<版本>.jar              # 同一次构建的后端 jar（热更新用）
desktop/dist/kura-edge-<版本>.jar.sha256       # 上面那份 jar 的摘要（sha256sum -c 可校验）
desktop/dist/feed.json                        # 自动更新清单（RFC 008，url 是相对路径）
```

`<版本>` 由构建时决定：默认取 `src-tauri/tauri.conf.json` 里的现值，`--version` 可以当场换（见下）。

两种产物来自同一条流水线、同一份 `-Pdesktop` jar，所以**热更新的 jar 必然自带匹配的前端**——
不存在"后端换了、页面还是旧的"这种错配。

安装到 `%LOCALAPPDATA%\kura-edge`，布局：

```
kura-edge.exe
resources/app/kura-edge.jar       # 后端 fat jar（-Pdesktop 构建，内含 static/ 前端产物）
resources/jre/bin/javaw.exe       # 内嵌 JRE 17（Temurin）
```

## 构建

```bash
bash desktop/scripts/build-desktop.sh                 # 完整安装包（exe + jar），沿用当前版本号
bash desktop/scripts/build-desktop.sh --jar-only      # 只产出可热更新的 jar
bash desktop/scripts/build-desktop.sh --version 1.0.4 # 指定版本号：写入四处 + 产物都叫 1.0.4
bash desktop/scripts/build-desktop.sh -V 1.0.4 --jar-only   # 参数可组合，-V 是 --version 的短名
# 或走 npm（参数用 -- 透传）：cd desktop && npm run build:jar -- --version 1.0.4
```

**指定版本号**：`--version X.Y.Z` 会把版本号写进四处——`src-tauri/tauri.conf.json`、`package.json`、
`src-tauri/Cargo.toml`、`update-core/Cargo.toml`——并同步两个 `Cargo.lock` 里本地 crate 的版本行，
随后 jar 名、`feed.json`、发布服务器投放目录、NSIS 安装包名全部用它。要求：
半角 `MAJOR.MINOR.PATCH`（可带 `-pre` / `+build` 后缀，Tauri 只认 semver）；传了与现值相同的号则只重新打包。
**降版本会警告**（设备端自动更新只升不降，低版本包只能用于重装/回滚验证）。
不传 `--version` 时行为与以前完全一致：沿用 `tauri.conf.json` 的现值。

**只改了后端/前端时用 `--jar-only`**：不重装 exe、不重新下载 JRE、不跑 NSIS，2 分钟出
`dist/kura-edge-<版本>.jar` + `.sha256`，末尾直接打印可以粘到设备上的 `curl`（见「后端热更新」）。
exe 外壳、`tauri.conf.json`、内嵌 JRE 有变化时仍须走完整模式——那部分是 exe 里的，换 jar 换不掉。

完整模式流水线：前端 `npm run build` → **`update-core` 纯逻辑测试（`cargo test`）** → 校验
`frontend/dist/index.html` 存在 → **先跑 `KuraEdgeApplicationContextTest`**（配置写坏当场拦下，
别等用户装完才发现后端起不来）→ 后端 `mvn -Pdesktop -pl kura-edge-rest -am -DskipTests package` →
发布 `dist/kura-edge-<版本>.jar` + `.sha256` → **重建 `resources/app`**（只放本次的 jar，历史残留
例如已下线子模块的 jar 不会被打进安装包）→ 下载/解压 Windows JRE →
`tauri build --runner cargo-xwin --target x86_64-pc-windows-msvc --bundles nsis` → 收 `dist/`。

脚本开头会校验**四处版本号一致**（`tauri.conf.json` / `package.json` / `src-tauri/Cargo.toml` /
`update-core/Cargo.toml`）：热更新靠版本号判定该不该装，不一致会让外壳以 `version_conflict`
拒装，所以直接 fail-fast 而不是等设备上才发现。手工改版本号时四处都得动；用 `--version` 则由脚本
统一写入并自检（sed 没匹配上会当场报错，不会静默出一个版本号没变的包）。

### 交叉编译前提（Linux 主机，全部用户态、不需要 sudo）

| 依赖 | 安装方式 | 本机位置 |
|---|---|---|
| Rust stable + `x86_64-pc-windows-msvc` target | `rustup-init -y --profile minimal` + `rustup target add x86_64-pc-windows-msvc` | `~/.cargo` |
| `cargo-xwin` | `cargo install --locked cargo-xwin`（首次构建自动拉 MSVC CRT/SDK 到 `~/.cache/cargo-xwin`，实测约 1.5 GB） | `~/.cargo/bin` |
| `lld-link` / `llvm-rc` | `apt-get download lld-14 llvm-14 libllvm14 && dpkg-deb -x …`，再软链成无版本号的名字 | `~/tools/tauri-cross/bin` |
| `clang-cl`（C 依赖需要） | `apt-get download clang-14 libclang-cpp14 && dpkg-deb -x …`，再 `ln -s clang-14 clang-cl` | `~/tools/tauri-cross/bin` |
| `makensis`（NSIS 3.x） | `apt-get download nsis nsis-common && dpkg-deb -x …`，`NSISDIR` 指向解压出的 `usr/share/nsis` | `~/tools/tauri-cross/nsis-linux` |
| `RestartManager.nsh` | Debian 的 nsis 包缺这个 include（tauri 的 installer.nsi 硬依赖），从 NSIS 上游取一份放到 `$NSISDIR/Include/Win/` | 已放在 nsis-linux 树内 |
| Node 20+ | 自带 | `~/nodejs/bin` |

路径都可用环境变量覆盖：`KURA_CROSS_HOME`、`KURA_NODE_BIN_DIR`、`KURA_CARGO_BIN_DIR`、
`KURA_MVN`、`KURA_NSIS_DIR`、`TAURI_TARGET`。

> 官方对 Linux→Windows 交叉编译的定性是「可行但属于最后手段，不如 Windows 主机构建稳」：
> 见 [Tauri Windows Installer 文档](https://v2.tauri.app/distribute/windows-installer/#build-windows-apps-on-linux-and-macos)。
> 有 Windows 机器或 CI 时，直接在 Windows 上 `npm run tauri build` 更省事（本目录工程与脚本不用改）。

## 运行时行为

0. **后端连 PostgreSQL**（RFC 007）：默认 `jdbc:postgresql://192.168.31.40:15432/kuraedge`，
   口令 `iot123...`，可用 `KURA_EDGE_DB_URL` / `KURA_EDGE_DB_USER` / `KURA_EDGE_DB_PASSWORD` 覆盖。
   设备只需要能**单向**访问数据库。连不上时后端直接启动失败（连接超时 10 s），外壳显示内置错误页。
   没有数据库的环境用 `--spring.profiles.active=h2` 退回文件 H2（`%USERPROFILE%\.kura-edge\data`），
   此时蓝绿不可用，升级会就地重启。外壳启动子进程时继承父进程环境变量，所以系统级设一次
   `KURA_EDGE_DB_PASSWORD` 就能对所有设备生效。
1. 外壳 `bind 127.0.0.1:0` 取空闲端口，并生成一次性随机口令（24 字节十六进制）。
2. `javaw -jar resources/app/kura-edge.jar --server.port=<P> --server.address=127.0.0.1`，
   环境变量 `KURA_EDGE_ADMIN_PASSWORD=<随机口令>`；stdout/stderr 重定向到
   `%LOCALAPPDATA%\kura-edge\logs\backend.log`。
3. 安装根目录先去掉 Windows verbatim 前缀（`\\?\D:\kura-edge` → `D:\kura-edge`）：
   Tauri 的 `resource_dir()` 会 `canonicalize`，而 Java 启动器的 CRT `open()` 打不开 `\\?\` 路径，
   表现为「尝试打开文件 \\?\… 时出现意外错误」（1.0.2 起修复；`plain_path()`，超 240 字符的长路径保留前缀）。
4. **选 jar**：优先 `%LOCALAPPDATA%\kura-edge\app\current.json` 记录的版本，其次它的 `previous`，
   最后安装目录基线 `resources/app/kura-edge.jar`；每个候选都要能读出 `PK\x03\x04` 头、不超过 1 GiB、
   （有记录时）sha256 一致，第一个通过的即启动（失败先重试 12×400 ms 等杀毒扫描结束）。
   首次启动或状态丢失时，把基线 jar 复制成 `app/kura-edge-<exe 版本>.jar` 并写下 `current.json`。
5. 先立刻显示内置启动页（避免白屏），后台每 250 ms 发 `GET /` 探测，**拿到 HTTP 200 才算就绪**
   （只看 TCP 连通会在端口被抢占时误判），随后窗口导航到 `http://127.0.0.1:<P>/`；上限 120 s。
6. 失败（JRE/jar 缺失、进程提前退出、超时）时同一页显示原因与日志路径。
7. 窗口关闭（`RunEvent::Exit`）时 `kill` 后端进程，不留孤儿 `javaw.exe`。

**凭据**：不写文件、不进前端 bundle。外壳通过 WebView `initialization_script` 注入
`window.__KURA_EDGE_AUTH__`，前端 `frontend/src/api/http.ts` 优先读它；桌面包构建时显式传空的
`VITE_WIRES_*`（真值判断，空串等于没配），抵消 `frontend/.env.local` 里的开发口令。

## 后端热更新（RFC 006/007，**只换 jar，不重启 exe**）

后端 jar 单独更新，安装包不用重装、窗口不用关。三种发起方式：

1. **页面按钮**（最常用）：左侧「Kura Edge」右边点「升级」，填 jar 网址即可；
2. `curl`（无人值守/脚本）；
3. 桌面外壳自己也只认 `staging/pending.json`，所以手工放一个 `pending.json` 一样能触发。

```bash
# 页面等价的最小请求：只填网址，sha256 与版本号由后端补齐
curl -u admin:<口令> -X POST http://127.0.0.1:<P>/api/v1/system/update \
  -H 'Content-Type: application/json' -d '{"url":"http://server/kura-edge-1.0.4.jar"}'
```

完整请求（带摘要，推荐）：

```bash
curl -u admin:<口令> -X POST http://127.0.0.1:<P>/api/v1/system/update \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://server/kura-edge-1.0.4.jar","sha256":"<64 位十六进制>","version":"1.0.4"}'
# 202 {"code":0,"message":"ok","data":{"version":"1.0.4","jar":"kura-edge-1.0.4.jar","sha256":"…"}}
```

旧设备升级的顺序：先 `build-desktop.sh --jar-only` 出 jar 发一轮（不停机、不重装），等下次需要
换 exe 时再走完整模式装一次——外壳启动时会用 `app\bundled.txt` 识别"安装器换过基线"，
不会因为新 exe 而把你已经热更上去的版本回退。

### 零中断（蓝绿）：默认路径，需要 PostgreSQL

新旧两个实例同时在线，靠 PostgreSQL 的**会话级咨询锁**决定谁有权跑图：抢到锁的叫 leader（跑图），
抢不到的叫 standby（HTTP 就绪但不跑图，因此**不会重复执行**定时器/驱动采集）。

1. 旧实例（leader）下载并校验新 jar，写下 `pending.json`，**自己不退出**，继续服务；
2. 外壳用新 jar 在新端口起 standby：它抢不到锁 → 起 HTTP 但不跑图；
3. 外壳等 standby 返回 200 → 把窗口导航过去（用户此刻已经在新版本上，没有黑屏等待）；
4. 外壳 `POST /retire` 让旧实例**停图 → 放锁 → 退出**；
5. standby 在 0.5 s 内抢到锁 → 恢复整图（同库快照）→ 成为 leader；
6. 外壳等 `role=leader` 确认后改 `current.json`、归档 `pending.json`、清理旧 jar。

结果是**页面零中断**、图执行只中断一次组件重建（亚秒到 1~2 s）。`GET /api/v1/system/version` 里的
`blueGreen` 表示是否具备这个能力，`role` 表示当前实例是 leader 还是 standby。

失败面（都不会把设备搞死）：standby 起不来 → 旧实例毫发无损，写 `rolled_back`；旧实例不受理
`/retire` → 强杀后等新实例接管；移交后新实例 20 s 内仍未成为 leader → 杀掉新实例、按旧指针重启旧 jar。

### 自动更新：设备定时拉清单（可选，RFC 008）

现场是"上位机只能单向访问服务器"，所以更新由**设备主动拉**。最省事的发法是直接用项目里的发布服务器
（`kura-edge-release-server`，8081，见它自己的 README）：

```bash
# 服务器上一行起服务（jar 已由 build-desktop.sh 投放到它的 releases/ 里）
cd kura-edge-release-server && java -jar target/kura-edge-release-server-*.jar
# 设备上只配发布服务器地址（清单路径由它推导，RFC 010）
KURA_RELEASE_SERVER_URL=http://192.168.31.40:8081
```

设备页面上「升级」弹窗会自动显示更新源与清单里的最新版本：比本机新时主按钮直接是
**「升级到 1.0.4」**，点一下就升级（地址与 sha256 都从清单来，不用手填链接）。

不想多起一个服务，也可以自己用 nginx/静态目录发——服务器上放两个文件：

```
releases/feed.json              ← build-desktop.sh 自动生成，指向最新 jar
releases/kura-edge-1.0.4.jar
```

设备侧配一个环境变量（系统环境变量，或外壳启动环境）：

```bash
KURA_RELEASE_SERVER_URL=http://192.168.31.40:8081      # 用发布服务器（推荐）
KURA_EDGE_UPDATE_FEED=http://192.168.31.40:8000/releases/feed.json   # 或用静态目录的清单（优先）
```

之后每 5 分钟拉一次清单（`KURA_EDGE_UPDATE_FEED_INTERVAL`，毫秒），发现**比本机新**的版本就自己下载、
校验 sha256，然后走与页面按钮完全相同的落包流程——蓝绿模式下页面零中断。配套端点：

```bash
curl -u admin:<口令> http://127.0.0.1:<P>/api/v1/system/feed         # 状态：开没开、上次结果、已提交/失败版本
curl -u admin:<口令> -X POST http://127.0.0.1:<P>/api/v1/system/feed/check   # 不等轮询，立即检查一次
```

几条护栏：**只升不降**（回退走页面手动升级）；`pending.json` 还在就不覆盖；同一版本不重复提交；
失败/回滚过的版本有 30 分钟冷却（`KURA_EDGE_UPDATE_RETRY_AFTER`），避免一台装不上的设备反复下载 60 MB；
本机版本是 `dev`/`unknown` 时不升级（别把开发机刷成发布版）。页面「升级」弹窗里能看到这套状态。

### 就地重启：文件 H2 模式

没有 PostgreSQL（`--spring.profiles.active=h2`）时不可能有第二个实例，只能停旧起新。旧实例收到
`pending.json` 后自己退出，外壳用同一端口重启新 jar，失败自动回滚：

1. 后端下载到 `staging/kura-edge-1.0.4.jar.part`，边下边算 sha256，超限即中止；
2. 摘要不符 → 400004，**设备不受影响**，旧后端继续跑；一致则落成正式名 + 写 `staging/pending.json`；
3. 后端约 1.5 s 后**自己优雅退出**（`server.shutdown=graceful`，H2 刷盘、连接池归还）；
4. 外壳把 jar `rename` 进 `app/kura-edge-1.0.4.jar`、改 `current.json`（旧版降为 `previous`）、
   用**同一端口、同一口令**重启 JVM；
5. 探测到 HTTP 200 → 窗口导航到 `http://127.0.0.1:<P>/?v=1.0.4`，直接就是新版本的前端；
6. 新版本 120 s 内起不来 → 自动回滚到 `previous` 并重启它，页面照常可用。回滚只发生一次，不会来回抖。

`GET /api/v1/system/version` 返回当前运行的版本、jar 路径与来源（`desktop` / `maven`）。

目录与文件（**单一写者**：`app\` 只有外壳写，`staging\` 只有后端写）：

```
%LOCALAPPDATA%\kura-edge\
├─ app\                     外壳独占写
│   ├─ current.json          ← 唯一真相：下次启动用哪个 jar
│   ├─ kura-edge-1.0.4.jar   ← 热更装进来的（旧版 kura-edge-1.0.3.jar 留作回滚）
│   ├─ bundled.txt           ← 安装目录基线 jar 的身份（版本/大小/mtime），识别 exe 升级
│   └─ apply-result.json     ← 最近一次结果：applied / rolled_back / rejected（含 error）
├─ staging\                 后端独占写
│   ├─ pending.json          ← "请求外壳应用"的唯一信号
│   └─ pending.applied-<ts>.json  ← 处理完的请求归档，便于事后对账
└─ logs\backend.log
```

**回滚边界**：只回滚到 `previous`（上一版**已成功启动**的 jar）。数据库 schema 只前滚
（Flyway + `ddl-auto: validate`），所以**带 schema 迁移的版本不可回滚**——旧 jar 会因 validate 失败
起不来，只能靠人工换回（或重装）。发布跨迁移版本时按「不可回滚」对待。

**不包含**：exe 与内嵌 JRE 的自动更新（JRE 被运行中的 `javaw.exe` 占用，换不掉）；灰度/分批；
多设备并发调度与回执汇总（每台设备自己看 `feed-state.json` / `apply-result.json`）；
签名校验（当前只有 sha256 + HTTPS）。

### 手工验证（Windows）

```bash
# 1) 蓝绿正常路径：页面点「升级」填新 jar 网址 → 窗口几乎无感切换，/version 的 version 变新、role=leader
# 2) 摘要不符：带一个错的 sha256 → 400004，页面继续可用（旧实例没退出，仍在跑图）
# 3) standby 起不来：传一个能过校验但启动必失败的 jar（如 --spring.config.location 指向不存在文件）
#    → 旧实例继续服务，apply-result.json 里 status=rolled_back；日志里能看到"standby 未就绪，已放弃本次更新"
# 4) 确认没有重复执行：升级完成后 PG 上只应有一个 session 持有咨询锁
#    select pid, state, query from pg_stat_activity where query like '%advisory%';
#    select count(*) from pg_locks where locktype='advisory';
# 5) 单机模式回归：--spring.profiles.active=h2 启动 → 升级会中断 15~25 s，页面自动回到新版本
```

## 许可与分发

本项目基于 **Eclipse Kura（EPL-2.0）** 派生，安装包内包含：

| 组件 | 许可 |
|---|---|
| kura-edge 自身（含本仓库代码） | 遵循上游 Kura 的 EPL-2.0 派生约定（`Copyright (c) 2026 kura-edge contributors. 基于 Eclipse Kura（EPL-2.0）代码修改。`） |
| Temurin 17 JRE（Adoptium） | GPLv2 + Classpath Exception |
| Tauri 2 / WRY / TAO | MIT 或 Apache-2.0 |

对外分发前请自行补齐 LICENSE/NOTICE 文件与第三方许可清单（本仓库暂无根 README/LICENSE）。

## 已知限制（当前版本）

- **未在 Windows 实机验证**：构建机是 Linux，产物只做到「交叉编译通过 + 包内容正确 + 后端托管实测」。
  首次拿到 exe 请按下面清单冒烟。
- 数据库为文件模式 H2（RFC 005 D4 已被推翻）：图与驱动配置落盘到
  `%USERPROFILE%\.kura-edge\data\kuraedge.mv.db`，重启窗口不丢；卸载安装包**不会**删除该目录，
  卸载后如需彻底清空要手动删 `%USERPROFILE%\.kura-edge`。排障时可用环境变量 `KURA_EDGE_DATA_DIR` 把库换到别的目录。
- 只有 NSIS `setup.exe`；MSI（WiX）只能在 Windows 主机构建。
- 无代码签名：SmartScreen 会提示未知发布者。
- **无 exe / 内嵌 JRE 自动更新**：后端 jar 已可热更新（见上节），但外壳本身与 JRE 仍要重装安装包；
  JRE 被运行中的 `javaw.exe` 占用，无法热换。
- 无托盘、无多实例保护：双开 exe 时两个外壳都盯着同一个 `staging\`（apply 因"版本已就绪则跳过"而幂等，
  但两个 JVM 抢同一个 H2 库文件仍是既有风险，`AUTO_SERVER` 只能缓解）。
- 内嵌 JRE 走 Adoptium `latest/17` 且未校验 SHA-256：同一提交在不同日子构建会内嵌不同 JRE 小版本。

## Windows 侧冒烟清单

1. 双击 `kura-edge_1.0.3_x64-setup.exe` → 默认装到 `%LOCALAPPDATA%\kura-edge`，不需要管理员权限。
2. 双击桌面/开始菜单图标 → 5~15 s 内出现窗口，停留在 `/wires` 页面且画布可交互。
3. 打开 `%LOCALAPPDATA%\kura-edge\logs\backend.log`，应看到 Spring Boot 启动日志，无 `Exception`。
4. 新建一个 Timer → Logger 连线并保存，刷新窗口（Ctrl+R 若可用）后图仍在；**退出并重开窗口后图仍在**（文件库落盘）。
5. 关闭窗口 → 任务管理器里不应残留 `javaw.exe`。
6. 卸载（设置 → 应用）后 `%LOCALAPPDATA%\kura-edge` 应被清理（数据在 `%USERPROFILE%\.kura-edge`，按设计保留）。
7. **热更新成功路径**：`curl -u admin:<口令> -X POST .../api/v1/system/update -d '{"url":…,"sha256":…,"version":"1.0.4"}'`
   返回 202 → 窗口出现「正在更新后端…」→ 15~25 s 后自动回到页面，`GET /api/v1/system/version` 显示 `1.0.4`。
   过程中 exe 进程 PID 不变（任务管理器里盯着看）。
8. **摘要不符**：把 sha256 改一位 → 返回 400004，页面完全不受影响（旧后端没退出），
   `app/apply-result.json` 里 `status: "rejected"`。
9. **回滚**：用一个 sha256 正确但启动必失败的 jar → 后端换完起不来，外壳自动回滚，页面仍是旧版本；
   `apply-result.json` 里 `status: "rolled_back"` 且 `error` 写明原因。

## 排障

| 现象 | 排查 |
|---|---|
| 窗口停在「正在启动」超过 2 分钟 | 看 `%LOCALAPPDATA%\kura-edge\logs\backend.log`；多半是 JRE/jar 缺失或端口被劫持 |
| 提示「找不到内嵌 JRE / 后端 jar」 | 安装目录 `resources\jre\bin\javaw.exe`、`resources\app\kura-edge.jar` 是否在 |
| 启动即报「尝试打开文件 `\\?\…\resources\app\kura-edge.jar` 时出现意外错误」 | **1.0.2 前为已知缺陷**：启动器把 Tauri `resource_dir()` 的 verbatim 路径（`\\?\`）直接传给 `javaw -jar`，Java 的 CRT 打不开 → 升级到 1.0.2。旧版本的排查：1.0.0 的启动器没有预检：先看 jar 大小是否为 60,424,703 字节（被截断=重装）；再用同目录 JRE 直接跑 `resources\jre\bin\java.exe -jar resources\app\kura-edge.jar` 复现，多半是杀软占用或安装目录 ACL；1.0.1 起会自动重试并回退到 `%LOCALAPPDATA%\kura-edge\app\` 副本启动，`backend.log` 顶部会写 `[launcher]` 行 |
| `tauri build` 报 `io: unexpected end of file`（停在 `Downloading nsis_tauri_utils.dll`） | GitHub release 资产网络不通且 tauri 每次都重下：可从已构建的安装包里取出该 dll 后手工编译——`7z x 旧安装包 \$PLUGINSDIR/nsis_tauri_utils.dll`，放进 `$NSISDIR/Plugins/x86-unicode/`，到 `target/<triple>/release/nsis/x64/` 跑 `makensis installer.nsi`（把 VERSION/OUTFILE 改成目标版本） |
| 页面能开但接口 401 | 外壳注入脚本没生效（窗口不是由外壳创建的），检查 `http.ts` 的 `__KURA_EDGE_AUTH__` 分支 |
| 构建期 `cargo xwin` 卡在 `Downloading MSVC CRT` | 该 CDN 的 IPv6 路由极慢（实测 8 KB/s vs IPv4 540 KB/s）：起个强制 IPv4 的本地代理并 `export HTTPS_PROXY=http://127.0.0.1:8899` 再跑（tauri CLI 下载 `nsis_tauri_utils.dll` 同理，失败会报 `io: unexpected end of file`） |
| `cargo xwin` 停在 `Blocking waiting for file lock` | 多个 cargo 抢 `~/.cargo/.package-cache` 锁，先 `pkill -f 'cargo xwi[n]'` 再串行构建 |
| makensis 报 `reading stub "/usr/share/nsis/Stubs/…"` | Tauri 启动 makensis 时**不传** `NSISDIR`：脚本已用 `desktop/dist/.bin/makensis` 包装器补设，检查它是否在 PATH 最前 |
| makensis 报 `could not find: "Win\RestartManager.nsh"` | 见上面前提表，把该 include 放进 `$NSISDIR/Include/Win/` |
| `tauri build` 报 `Permission denied (os error 13)` | 内嵌 JRE 里的 `classes.jsa` 是只读 0555，tauri 拷资源时 `O_TRUNC` 被拒：脚本已 `chmod -R u+w`，手工构建时也要先放开（并删掉 `target/<triple>/release/resources`） |
| 装完启动失败，`backend.log` 报 `Unsupported connection setting "DB_CLO RSE_DELAY" [90113-224]` | **1.0.3 及更早版本**的 H2 内存库 URL 被写坏（`DB_CLOSE_DELAY` 中间多一个空格，H2 把参数名当非法设置）。当前版本改用文件库（`kura-edge-rest/src/main/resources/application.yml:12`，`AUTO_SERVER=TRUE`），URL 里已无该参数；若仍见到此报错，说明跑的是旧 jar，重新打包/重装 |
| 后端启动报 `Database may be already in use` / `Locked by another process` | 同一个库文件被另一个进程以**非 AUTO_SERVER** 方式占用（例如 IDE 里另连了一份、或旧版内存库进程）。AUTO_SERVER 在数据库首次打开时生效：先关掉占用进程再启动；确认无进程占用后可删 `%USERPROFILE%\.kura-edge\data\kuraedge.lock.db` 后重试 |
| 热更新返回 `409004`「当前不是桌面运行时」 | 后端没拿到 `KURA_EDGE_UPDATE_DIR` / `KURA_EDGE_HOME`：只有外壳拉起的 JVM 才有；`java -jar` / IDE 里跑就是不支持（故意的，避免"看起来更新了其实没人换 jar"） |
| 热更新返回 `409005` | 上一次更新还在进行（后端还没退出）；等它退出或重启 exe |
| 返回 202 但窗口一直停在「正在更新后端…」 | 看 `%LOCALAPPDATA%\kura-edge\logs\backend.log` 里的 `[update]` 行与 `app\apply-result.json`；多半是新 jar 起不来（缺依赖/配置写坏），已回滚 |
| 装了新 exe 但版本号没变 | 安装器换过基线 jar 时外壳会重新落地（`app\bundled.txt` 记录基线身份）；若仍旧，看 `backend.log` 的 `[launcher] 基线已落地` 行，或手动删 `app\current.json` 后再启动 |
| 构建产物里混进上一版前端指纹文件 | `-Pdesktop` 会先清 `target/classes/static`；若仍异常，先 `mvn clean` |
