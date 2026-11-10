# RFC 005 — 桌面化打包：Tauri 2 外壳 + 内嵌 JRE 17 + NSIS 安装包

- 状态：**已批准（用户 2026-11-03 回复「批准，开始实现」）→ 已实现，待 Windows 实机冒烟**
- 提出：2026-11-03
- 档位：**L**（新增子工程 `desktop/` + 构建打包链路 + rest 模块静态托管与安全放行 + 前端运行时凭据注入）
- 相关规则：rules/04（分层；rest 是唯一启动模块、唯一允许 Controller）、rules/05（边界与密钥）、
  rules/06（Gherkin + 覆盖率）、rules/07（REST/错误码）、`CLAUDE.md`「硬约束清单」。

## 0. 结果意象（用户视角，先于代码）

**现状**：kura-edge 只能起两个终端（`mvn spring-boot:run` + `npm run dev`），或手工 `java -jar`；
没有任何可分发、可双击安装的桌面产物。

**之后**：

- 双击 `kura-edge_1.0.0_x64-setup.exe` → NSIS 安装向导 → 装到 `%LOCALAPPDATA%\kura-edge`，
  开始菜单与桌面出现 "kura-edge" 图标。
- 双击图标：外壳进程先把内嵌 JRE 里的 Spring Boot 拉起来（只监听 `127.0.0.1:<随机端口>`），
  就绪后弹出原生窗口，内容是现在这套 Wires 图编辑器；关窗即退出，后端进程被一并结束。
- 目标机**不需要预装 Java**，也不需要浏览器（用系统自带的 WebView2）。
- 启动失败（端口占满、JRE 缺失、jar 崩溃）时窗口显示错误页 + 日志路径，而不是白屏或静默退出。

**不包含**（留给后续）：数据持久化（按用户决定保持内存库，重启即空图）、自动更新、代码签名/公证、
macOS/Linux 安装包、托盘与多窗口、任何后端业务功能变化、快照 JSON 结构变化。

## 1. 决策记录（已与用户确认）

| # | 岔路 | 选定 | 理由 |
|---|---|---|---|
| D1 | Java 运行时从哪来 | **内嵌 Temurin 17 JRE（Windows x64）** | 开箱即用；代价是安装包 ~300MB |
| D2 | exe 在哪构建 | **本机 Linux 交叉编译**（rustup + cargo-xwin + lld + NSIS） | 用户要成品 exe；Tauri 官方支持 NSIS 交叉编译 |
| D3 | 前端由谁托管 | **Spring Boot 托管 `frontend/dist`**（同源 `http://127.0.0.1:<port>`） | 无 CORS、浏览器访问方式不变 |
| D4 | 数据库 | ~~**保持内存 H2**（用户选择）~~ **已推翻（2026-02，用户要求持久化）：改文件模式 H2**，落盘 `~/.kura-edge/data/kuraedge`，`KURA_EDGE_DATA_DIR` 可覆盖；测试仍用内存库 | 原决策图省事但每次启动数据清空；用户在桌面化落地后要求本地持久化。详见 `kura-edge-rest/src/main/resources/application.yml` 的 `spring.datasource.url`；卸载不删数据目录，属已知限制 |
| D5 | 安装包形态 | **NSIS `-setup.exe`** | WiX/MSI 只能在 Windows 主机构建，Linux 上没有可行路径 |

实现层决策（不改变用户可见结果，我定）：

- **D6 端口与监听**：外壳每次启动时 `bind 127.0.0.1:0` 取空闲端口，用 `--server.port=N --server.address=127.0.0.1`
  传给后端；避免与用户机上别的 8080 冲突，也不把后端暴露到局域网。
- **D7 凭据不落盘、不进 bundle**：外壳生成一次性随机口令 → 子进程环境变量 `KURA_EDGE_ADMIN_PASSWORD`
  → 同时通过 WebView `initialization_script` 注入 `window.__KURA_EDGE_AUTH__ = {username, password}`；
  前端 `http.ts` 优先读它，其次才是 `VITE_WIRES_*`（保留现有构建期方式，不改既有行为）。
- **D8 失败兜底**：120s（评审后从 90s 上调，冷机解压+JIT 实测约 17s，留足余量）内后端未返回 HTTP 200，
  或子进程提前退出 → 窗口加载内置错误页（显示原因 + 日志路径）。
- **D9 日志**：后端 stdout/stderr 重定向到 `%LOCALAPPDATA%\kura-edge\logs\backend.log`，便于排障。
- **D10 SPA history 回退**：Vue Router 用 `createWebHistory`，浏览器里刷新 `/wires` 会 404；
  新增 `SpaForwardController` 显式转发 `SpaRoutes.ALL`（`/`、`/wires`、`/drivers-assets`）→ `index.html`，
  安全性放行表引用同一组常量，`SpaForwardControllerTest` 与 `SecurityConfig` 一起装配，锁住「放行表 ⊇ 转发表」。
  控制器带 `@ConditionalOnResource("classpath:static/index.html")`：普通 `mvn package` 的 jar 没有前端产物，
  不装配它，行为与改动前一致（否则 `/` 会变成 500）。

## 2. 技术方案

### 2.1 结构（新增 `desktop/`，与 frontend/ 平级）

```
desktop/
├─ package.json                 # 只装 @tauri-apps/cli；脚本 build:exe / build:desktop / fetch:jre
├─ scripts/build-desktop.sh     # 一键：前端 dist → rest jar → 拉 JRE → tauri build → 收 exe
├─ scripts/fetch-windows-jre.sh # Temurin 17 JRE zip（带缓存，不重复下载）
├─ src-tauri/
│  ├─ Cargo.toml / build.rs     # tauri 2.x，无额外插件（不引入 dialog/updater）
│  ├─ tauri.conf.json           # identifier com.kura.edge；bundle.resources = app/ + jre/；targets=["nsis"]
│  ├─ capabilities/default.json # 仅 core:default 能力
│  ├─ icons/…                   # 由 1024px PNG 生成（tauri icon）
│  ├─ resources/                # 构建产物，gitignore
│  │  ├─ app/kura-edge.jar
│  │  └─ jre/…                  # javaw.exe + 运行时
│  └─ src/main.rs               # 拉起 JRE → 轮询端口 → 建窗 → 退出时 kill
└─ error/index.html             # frontendDist 占位 + 启动失败错误页
```

`kura-edge-rest` 侧：

- `pom.xml` 增加 profile `desktop`：`maven-resources-plugin` 把 `../frontend/dist/**` 拷进
  `target/classes/static/`（只进 target，不污染 `src/main/resources`，也不入库）。
- `SecurityConfig`：放行静态资源（`/`、`/index.html`、`/assets/**`、`/favicon.ico`、三个 SPA 路由），
  `/api/**` 与其余端点仍要求认证（rules/05 不变）。
- 新增 `SpaForwardController`（rest 模块，唯一允许 Controller 的地方）。

### 2.2 运行时时序

```
tauri main()
  ├─ 解析 resource_dir → jre/bin/javaw.exe、app/kura-edge.jar
  ├─ 取空闲端口 P；生成随机口令 PW
  ├─ 立刻建窗（内置启动页），并注入 initialization_script("window.__KURA_EDGE_AUTH__=…")
  ├─ spawn(javaw -jar kura-edge.jar --server.port=P --server.address=127.0.0.1
  │          --spring.main.banner-mode=off, env KURA_EDGE_ADMIN_PASSWORD=PW)
  ├─ 每 250ms 发 "GET /" 探测，要求 HTTP 200（只看 TCP 连通会在端口抢占时误判），最长 120s
  ├─ 就绪 → window.navigate(http://127.0.0.1:P/)；失败 → 同一页显示错误与日志路径
  └─ RunEvent::Exit → child.kill() + wait()
```

Rust 依赖只用 `tauri` + `rand` + `serde_json`（初始化脚本转义用；不引入 reqwest，端口探测走 `std::net` 手写最小 HTTP 请求）。

### 2.3 构建流水线（`make` 之外的唯一入口：`desktop/scripts/build-desktop.sh`）

1. `frontend: npm run build`（不设 `VITE_WIRES_*`，凭据走 D7 运行时注入）
2. `mvn -Pdesktop -pl kura-edge-rest -am -DskipTests package`（产物 = repackage 后的 fat jar）
3. 拷贝 jar → `desktop/src-tauri/resources/app/kura-edge.jar`
4. `fetch-windows-jre.sh` → 解压到 `desktop/src-tauri/resources/jre/`（校验 `bin/javaw.exe` 存在）
5. `npx tauri build --runner cargo-xwin --target x86_64-pc-windows-msvc --bundles nsis`
6. 产物收集到 `desktop/dist/kura-edge_<ver>_x64-setup.exe`

工具链（本机，用户态，已装）：`~/.cargo`（rustup stable + `x86_64-pc-windows-msvc` + `cargo-xwin`）、
`~/tools/tauri-cross/llvm`（lld-link / llvm-rc）、`~/tools/tauri-cross/nsis`（makensis）。

### 2.4 变更清单（会动到的文件）

| 文件 | 变更 | 测试 |
|---|---|---|
| `desktop/**` | 新增 Tauri 外壳与构建脚本 | `cargo xwin check` 通过 + 安装包产出 |
| `kura-edge-rest/pom.xml` | 新增 `desktop` profile（拷 dist） | 构建期验证 |
| `kura-edge-rest/src/main/java/.../controller/SpaRoutes.java` | 新增（路由单一真源） | 由下面两条测试间接覆盖 |
| `kura-edge-rest/src/main/java/.../controller/SpaForwardController.java` | 新增（`@ConditionalOnResource`） | `SpaForwardControllerTest`（3 条路由匿名 200 + forward） |
| `kura-edge-rest/src/main/java/.../security/SecurityConfig.java` | 放行静态 + SPA 路由 | `StaticResourceSecurityTest`（静态 200 / 缺失 404 / `/api/**` 401） |
| `kura-edge-rest/src/main/java/.../advice/GlobalExceptionHandler.java` | 新增 `NoResourceFoundException` → 404001 | 同上（`/assets/missing-hash.js` → 404 + code 404001） |
| `kura-edge-rest/src/test/resources/static/` | 测试用静态探针（与 dist 同构） | 让「匿名可读」是 200 而不是 404 蒙混 |
| `frontend/src/api/http.ts` | 读 `window.__KURA_EDGE_AUTH__` | `http.spec.ts`（有/无注入两种路径） |
| `.gitignore` | 忽略 `desktop/src-tauri/resources/`、`desktop/dist/`、`desktop/src-tauri/target/` | — |
| `CLAUDE.md` | 「工程结构」「常用命令」补桌面打包一节 | — |

**不动**：图引擎、快照 JSON、`/api/v1/wires/**` 语义、端口模型、错误码表、rules 原文。

## 3. 风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| Linux→Windows 交叉编译失败（cargo-xwin 拉 MSVC CRT 失败 / 链接报错） | 中 | 先跑最小 Tauri 样板验证链路，再挂本项目；失败则回退「GitHub Actions windows-latest」出 exe，工程与脚本不变 |
| NSIS 打包 300MB 资源耗时/失败 | 中 | `bundle.resources` 用目录映射，先验证 5MB 占位资源能打包 |
| WebView2 在目标机缺失 | 低 | NSIS 默认 `downloadBootstrapper`（需联网）；Win11 自带 |
| **产物无法在本机运行验证**（无 Windows 主机 / 无 wine） | 高 | 交付时明确标注「未在 Windows 上实机验证」，给出用户侧冒烟清单（README「Windows 侧冒烟清单」） |
| 装入 300MB 后安装包首启慢（JRE 解压） | 低 | NSIS 解压本身是顺序 I/O，实测后写进 README |
| 与另一会话的未提交改动冲突 | 低 | 只动上表文件；不动其 16 个改动文件 |

## 4. 验收（DoD）

1. `desktop/scripts/build-desktop.sh` 一条命令跑通，产出 `desktop/dist/kura-edge_1.0.0_x64-setup.exe`。
2. 安装包内含：`resources/app/kura-edge.jar`、`resources/jre/bin/javaw.exe`、`kura-edge.exe`。
3. 后端能托管前端：`mvn -Pdesktop -pl kura-edge-rest -am package` 后 `jar tf` 能看到 `BOOT-INF/classes/static/index.html`。
4. 安全不回退：`/api/v1/wires/**` 未认证仍 401；静态资源匿名可访问（单测覆盖）。
5. 前端 `npm run lint && npm run type-check && npm run test:unit && npm run build` 通过。
6. 后端 `mvn clean verify` 通过（含 ArchUnit 与覆盖率门槛）。
7. `cargo xwin check --target x86_64-pc-windows-msvc` 通过（Rust 侧编译正确性）。
8. 文档：`desktop/README.md` 写清构建前提、产物路径、**未在 Windows 实机验证**的说明与用户侧冒烟步骤。

## 5. 未决问题（Open questions）

- 无阻塞项。D1–D5 已由用户选定；D6–D10 属实现细节，已在此记录理由。

## 6. 实施记录（2026-11-03，本机 Linux 交叉编译）

| 步骤 | 结果 |
|---|---|
| 交叉编译 | `cargo xwin check/build --target x86_64-pc-windows-msvc` 通过；release 二进制已产出 |
| 打包 | `kura-edge_1.0.0_x64-setup.exe`，**78.46 MiB**（payload：`kura-edge-desktop.exe` 7.2 MB + jar 60.4 MB + 317 个 JRE 条目，LZMA 压缩） |
| jar 内容 | `BOOT-INF/classes/static/` 只有当前 9 个文件（stale 指纹文件已被 `-Pdesktop` 的 clean 掉） |
| 包内校验 | `7z l` 确认含 `kura-edge-desktop.exe`、`resources/app/kura-edge.jar`、`resources/jre/bin/javaw.exe`、`resources/jre/bin/server/classes.jsa`；jar 与 `kura-edge-rest/target` 产物 md5 一致 |
| 同源托管实测 | `java -jar … --server.port=18099 --server.address=127.0.0.1`：`/`、`/wires`、`/assets/*.js` 均 200；`/api/v1/wires/metadata` 匿名 401（code 401001）、带 `admin:<KURA_EDGE_ADMIN_PASSWORD>` 200；`ss` 确认只监听 127.0.0.1 |
| 后端门禁 | `mvn -B -o clean verify` 通过（含 ArchUnit、JaCoCo、新增 4 条测试） |
| 前端门禁 | `npm run lint`、`npm run type-check`、`npm run test:unit`（含新增 4 条凭据用例）、`npm run build` 通过 |

评审后按三轴（Standards / Spec / Fidelity）结论修订的点：

1. 非 desktop 构建被 SPA 转发污染（Fidelity major）→ 控制器加 `@ConditionalOnResource`，并把
   `NoResourceFoundException` 映射成 404001（原来兜底成 500）。
2. `-Pdesktop` 的 `copy-resources` 只增不删，旧前端指纹文件会进包（Spec major）→ profile 里先
   用 `maven-clean-plugin` 清 `${project.build.outputDirectory}/static`；脚本再 fail-fast 校验 `frontend/dist/index.html`。
3. 路由清单三处重复（Standards major）→ 抽出 `SpaRoutes`，控制器与安全放行表共用。
4. 测试 `then*` 步骤空壳（Standards major）→ 断言移进 `then*` 私有步骤。
5. `resources/jre/.gitkeep` 被 `fetch-windows-jre.sh` 的 `rm -rf` 删掉（两条轴都报）→ 解压后 `touch` 回来。
6. 分发物缺许可声明（Standards major）→ `desktop/README.md` 增「许可与分发」一节（EPL-2.0 派生 + Temurin GPLv2+CE + Tauri MIT/Apache）。
7. 交叉编译四个坑（本机实测）写进 README 排障表：IPv6 CDN 慢（本地 IPv4 代理）、cargo 锁竞争、
   Tauri 不传 `NSISDIR`（makensis 包一层包装器）、`RestartManager.nsh` 缺失、JRE `classes.jsa` 只读导致 `O_TRUNC` EACCES。
8. 未采纳/记录的偏差：`STARTUP_TIMEOUT` 120s（D8 已回写）；`serde_json` 依赖（已回写）；
   窗口先显示启动页再 navigate（已回写）；构建期空串凭据不再发 `Basic Og==`（真值判断，属有意为之）。

## 7. 现场缺陷与修复（1.0.1 → 1.0.2）

Windows 实机首启报 `Error: 尝试打开文件 \\?\D:\kura-edge\resources\app\kura-edge.jar 时出现意外错误`。

- **根因**：`install_root()` 优先取 Tauri 的 `resource_dir()`，该调用会 `canonicalize`，在 Windows 上返回
  verbatim 路径（`\\?\D:\...`）。这个前缀对 Rust `File::open` 无害，但 **OpenJDK 的 `jli` 用 CRT `open()`
  打开 jar，不认 verbatim 前缀**，于是「文件在、Rust 能读（日志打出 60,424,703 字节）、Java 读不了」。
- **修法**：`plain_path()` 去掉 `\\?\`/`\\?\UNC\` 前缀（超过 240 字符的长路径保留前缀，否则 Win32 打不开），
  `install_root()` 两个返回分支都过一遍。
- **同时加固**（1.0.1 起）：jar 可读性预检（校验 `PK\x03\x04`）+ 12×400 ms 重试 + 复制到
  `%LOCALAPPDATA%\kura-edge\app\` 回退 + 错误页给出两侧路径/字节数/原因 + `backend.log` 追加 `[launcher]` 行。
