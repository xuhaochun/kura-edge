# CLAUDE.md — kura-edge

## 项目定位

kura-edge 是从 Eclipse Kura 生态拆分出的 **Wires Graph（线图）功能独立项目**的后端重写 + 前端重写。
目标：脱离 OSGi 容器与 GWT 旧栈，用 **Spring Boot（Java 17）+ Vue 3** 重建 Kura 的 Wires 数据流编排能力，
保留上游的领域模型、快照格式与 REST 语义，作为二开（二次开发）的基础。

## 上游参考仓库（本地已克隆，**只读**，禁止修改）

| 本地路径 | 上游仓库 | 本项目参考内容 |
|---|---|---|
| `/home/idea/IdeaProjects/kura` | eclipse-kura/kura | 领域 API：`kura/org.eclipse.kura.api/src/main/java/org/eclipse/kura/wire`（WireComponent/WireEnvelope/WireRecord 等）与 `org/eclipse/kura/wire/graph`（WireGraphService/WireGraphConfiguration/MultiportWireConfiguration 等）；编码与测试规范见其 `AGENTS.md` |
| `/home/idea/IdeaProjects/kura-wires` | eclipse-kura/kura-wires | 图引擎实现：`bundles/org.eclipse.kura.wire.provider`（WireGraphServiceImpl）；内置组件：`wire.component.provider`（Timer/Logger/RegexFilter/CloudPublisher/CloudSubscriber/Fifo/WireAsset）、`db.component.provider`、`script.tools`（Conditional/Filter）、`ai.component.provider`、`asset.provider`；REST 参照：`org.eclipse.kura.rest.wire.provider`（WireRestService，`/wire/v1`） |
| `/home/idea/IdeaProjects/kura-management-ui` | eclipse-kura/kura-management-ui | 旧前端：GWT `org.eclipse.kura.web2`（`client/ui/wires/` 下的 WiresPanelUi/ConfigurationAreaUi/ValidationData 等）+ 原生 JS SVG 图编辑器 `src/main/resources/org/eclipse/kura/web/js/wires_composer.js`（696 行，含环检测/缩放/网格/端口闪烁） |

## 核心迁移对照表（上游 → kura-edge）

| 上游（OSGi/GWT） | kura-edge（Spring/Vue） |
|---|---|
| OSGi Declarative Services + ConfigurationAdmin | Spring 容器 + 数据库持久化的组件配置服务 |
| `WireGraphServiceImpl`（依赖 OSGi WireAdmin） | `kura-edge-core` 的 `WireGraphEngine`（纯 Java，自行管理组件实例与连线） |
| `org.eclipse.kura.wire.graph` API 包 | `kura-edge-api` 模块（去 OSGi 注解，保留类名与字段名） |
| `WireRestService`（JAX-RS，`/wire/v1`，角色 `wires.admin`） | `kura-edge-rest`（Spring MVC，`/api/v1/wires/**`，Spring Security 同名角色） |
| GWT `WiresPanelUi` + `wires_composer.js` | Vue 3 图编辑器组件（Vue Flow / 自研 SVG composer） |
| 快照 = ConfigurationAdmin snapshot + `WireGraph` 字符串属性 | 快照 = 数据库表 + 相同的 `WireGraph` JSON 结构 |

**必须保留的上游语义（详见 `.dsh/rules/08-additional-rules.md`）：**
多端口模型（`emitterPort`/`receiverPort`、`inputPortCount`/`outputPortCount`）、
快照图 JSON 字段名（`WireGraph` 属性内为 `components` / `wires`：组件条目 `pid`/`inputPortCount`/`outputPortCount`/`renderingProperties.position.{x,y}`，连线 `emitter`/`emitterPort`/`receiver`/`receiverPort`，便于导入 Kura 原生快照）、
图更新前的环检测与端口兼容校验。

## 硬约束清单（不读 rules 也必须遵守）

从 `.dsh/rules/` 提炼的 build-breaking / review-blocking 条目；本节是摘要，详细论证与例外见各 rules
原文，**冲突时以 rules 为准**。改 rules 时同步改本节，别让两处漂移。

### 依赖与分层（rules/04，ArchUnit 强制，违反即 `mvn verify` 失败）

- `api` 只依赖 JDK（Jackson 注解可）：禁止 Spring/JPA/其他模块；`core` 禁止依赖 `rest`/`components`；
  `components` 禁止依赖 `rest`；禁止反向/循环依赖。
- `rest` 是唯一**业务**启动模块；唯一例外是 `release-server`（8081，只发 jar 与 `feed.json`），
  它只能依赖 `api`，不许碰 core/components/rest 与数据库（RFC 009）。
- Controller 只做 DTO ⇄ 领域模型转换、Bean Validation、权限注解、调 Service（方法超 ~30 行说明逻辑没下沉）；
  DTO 不得作 Service 入参，领域模型不得引用 DTO，Repository 只被 Service 调用。
- 异常只从 Service 抛 `WiresException` + `ErrorCode`，由 `GlobalExceptionHandler` 统一转换；
  组件内部异常由引擎捕获记录，不中断整图。

### 边界禁令（rules/05）

- `{kura,kura-wires,kura-management-ui}` 只读：只允许 read/grep，禁止修改、提交、在其目录跑写命令。
- 禁止 OSGi 依赖与编程模型（DS 注解、BundleContext、ServiceTracker）；禁止 Lombok、MapStruct、Hutool。
- 禁止 `System.out/err`、`printStackTrace`、吞异常；禁止绕过 Repository 直接执行 SQL。
- 快照 JSON 字段名与结构不许改（只可追加）；密码字段出 DTO 必须脱敏；`/api/v1/wires/**` 全部要求 `wires.admin`。
- 密钥只进环境变量或未提交配置；禁止为过覆盖率写无断言测试；禁止跳过测试提交。

### 命名与格式（rules/02）

- 包名 `com.kura.wires.*`；移植类保留上游类名与核心方法名（`WireEnvelope`、`get()/update()/delete()`）。
- 行宽 ≤ 150；Java 4 空格 / Vue·TS 2 空格；LF + UTF-8；禁止 `import *` 与未使用 import；字段注入改构造器注入。
- 公共 API（api 接口、对外 DTO）必须有中文 Javadoc/TSDoc；TODO 必须带责任人。

### 测试（rules/06）

- Gherkin 强制：`@Test public void behaviorName()` + `given*/when*/then*` 私有步骤，正反路径都覆盖；
  一个测试类只覆盖一个特性，复杂特性拆多个类。
- 覆盖率门槛：core ≥ 85%、api/components ≥ 80%、rest ≥ 70%，不达标 `mvn verify` 失败。
- 必测：环检测、端口超界、快照往返、图更新重建顺序（stop → reconnect → start）。
- **测试反模式（评审必查）**：实现耦合、同义反复（断言在任何实现下都不可能红）、横向切分；
  M/L 级变动的 RFC 必须预先约定测哪些 seam。识别线与 seam 定义见 `.dsh/rules/06-testing.md`。

### REST（rules/07）

- `code = HTTP 状态码 + 3 位业务序号`，枚举只放 `kura-edge-api` 的 `ErrorCode`，禁止随手造码。
- 参数错 → 400（DTO 上的 Bean Validation）；拓扑错 → 409（环 `409001`、端口不匹配 `409002`，在 `WireGraphEngine.validate()`）。
- 多步操作（整图保存）走 FailureHandler 语义：尽量做完 + 聚合报错，不留半新半旧。

### 提交前（rules/05）

- 后端 `mvn clean verify`；前端 `npm run lint && npm run type-check && npm run test:unit && npm run build`。
- 改了哪个模块至少跑该模块测试，并整体 verify 一次。

## 技术栈

- 后端：Java 17、Spring Boot 3.x、Maven 多模块、Spring MVC、Spring Security、Spring Data JPA、**PostgreSQL（默认）**、H2（`--spring.profiles.active=h2` 单机/离线）、Jackson、JUnit 5 + Mockito + AssertJ + JaCoCo + ArchUnit、Flyway 11
- 前端：Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus、图编辑器用 Vue Flow（`@vue-flow/core`）、Vitest + Vue Test Utils、ESLint + Prettier

详细规范见 `.dsh/rules/01-tech-stack.md`。

## 工程结构

```
kura-edge/
├── pom.xml                    # 父 POM（Java 17）
├── .dsh/                      # AI 协作配置（DSH 技能发现路径）
│   ├── skills/                # 技能正文（真实目录）：wires-* + dev-workflow-change-management
│   └── rules/                 # 01–08 项目规范，被本文件与各技能引用
├── change/                    # 变更记录
│   ├── rfc/                   # RFC 001–…（M/L 级改动先写这里）
│   └── backlog/               # BL-NN 待办卡（当前为空）
├── kura-edge-api/             # 领域模型与 SPI：WireEnvelope、WireRecord、WireComponent、
│                              #   WireGraphConfiguration、MultiportWireConfiguration、组件 SPI
├── kura-edge-core/            # 图引擎：WireGraphEngine、环检测/端口校验、组件注册表、快照持久化
├── kura-edge-components/      # 内置组件：Timer、Logger、RegexFilter、DbStore、CloudPublisher、Fifo…
│                              #   Modbus/Simulated 驱动（同 JVM，@Component 工厂）
├── kura-edge-rest/            # Spring Boot 启动模块：Controller、DTO、全局异常、安全配置
├── kura-edge-release-server/  # 发布服务器（8081，RFC 009）：扫 releases/ 提供 jar + feed.json，只依赖 api
├── frontend/                  # Vue 3 前端（Vite）
├── desktop/                   # 桌面外壳（Tauri 2 + 内嵌 JRE 17），产出 NSIS setup.exe（RFC 005）
│                              #   后端 jar 热更新：staging/pending.json 文件协议 + update-core（RFC 006）
│                              #   自动更新：设备定时拉服务器 feed.json（RFC 008）
│                              #   更新源收敛到发布服务器地址 + 页面一键升级（RFC 010）
├── desktop/update-core/       # 热更新纯逻辑（纯 Rust，cargo test 可跑）：清单、jar 校验、原子写、更新决策
└── kura-edge-core/…/ha/      # 蓝绿选主（RFC 007）：PG 会话级咨询锁 + 引擎按 leader 门控
```

完整目录约定见 `.dsh/rules/03-project-structure.md`；分层与依赖规则见 `.dsh/rules/04-architecture-layers.md`。

## Maven 工具目录

Maven 目录在 `/home/idea/tools/apache-maven-3.9.11/`。

## 常用命令

```bash
# 后端（项目根目录）
mvn clean verify                 # 全量构建 + 测试 + JaCoCo 覆盖率检查 + ArchUnit
mvn -pl kura-edge-core test      # 只跑某模块测试
mvn spring-boot:run -pl kura-edge-rest   # 本地起后端（默认 8080，连内网 PostgreSQL）
mvn spring-boot:run -pl kura-edge-rest -Dspring-boot.run.profiles=h2   # 离线单机：文件 H2
# 数据库口令/地址用 KURA_EDGE_DB_URL / _USER / _PASSWORD 覆盖；蓝绿开关 KURA_EDGE_BLUEGREEN=auto|on|off
# 自动更新（RFC 008/010）：KURA_RELEASE_SERVER_URL=http://<服务器>:8081 即可（清单路径自动推导）；
#   静态目录发版再退回 KURA_EDGE_UPDATE_FEED=http://<服务器>/<目录>/feed.json（两者都配时它优先）

# 前端（frontend/）
npm install
npm run dev                      # 开发服务器（代理 /api → 8080）
npm run lint && npm run test:unit && npm run build

# 桌面安装包（desktop/；Linux 上交叉编译 Windows NSIS，产物 desktop/dist/*-setup.exe）
bash desktop/scripts/build-desktop.sh             # 完整包；详见 desktop/README.md
bash desktop/scripts/build-desktop.sh --jar-only  # 只出热更新 jar（dist/kura-edge-<版本>.jar + .sha256）
# 热更新纯逻辑（RFC 006）：解析/校验/原子写/更新决策，改动了就跑
cd desktop/update-core && cargo test

# 发布服务器（RFC 009）：把 kura-edge-<版本>.jar 放进 releases/，起 8081 供设备拉清单
mvn -o -pl kura-edge-release-server -am -Dmaven.test.skip=true package
cd kura-edge-release-server && java -jar target/kura-edge-release-server-*.jar
# 设备侧：KURA_RELEASE_SERVER_URL=http://<服务器>:8081（RFC 010；要给完整清单地址时才用 KURA_EDGE_UPDATE_FEED）
```

提交前验证命令与禁令清单见 `.dsh/rules/05-boundaries-and-verification.md`。

## 测试与 REST

- 测试：Gherkin 风格、测试类粒度、覆盖率门槛、必测场景见「硬约束清单 · 测试」；
  前端 Vitest 约定与二阶段集成测试见 `.dsh/rules/06-testing.md`。
- REST：资源化 URL、统一响应体 `{code, message, data}`、全局异常处理、完整错误码表见
  `.dsh/rules/07-restful-and-error-codes.md`；错误码构成与 400/409 归位见「硬约束清单 · REST」。

## 提交规范

沿用上游 Conventional Commits：`<type>(<scope>): <description>`，scope 用 Maven 模块短名
（如 `feat(core): add cycle detection`、`fix(frontend): wire drag offset`）。
类型：feat / fix / docs / refactor / test / style / chore / build / ci / revert / perf。

## AI 协作与变更流程

AI 约定集中在 `.dsh/`，变更记录在 `change/`。**仓库已不再使用 `.claude/`**（旧结构已并入本节）。

```
.dsh/
├── skills/          # DSH 技能正文（真实目录，不是软链）
└── rules/           # 01–08 项目规范
change/
├── rfc/             # NNN-<slug>.md，编号顺延
└── backlog/         # BL-NN.md（当前为空，pre-flight 的待办审计因此跳过）
```

DSH 只扫描 `.dsh/skills`、`.agents/skills`、`$DSH_HOME/skills`、`~/.agents/skills` 四处；
仓库内技能正文只维护 `.dsh/skills/<name>/SKILL.md`，新增/删除技能即增删该目录，**不再需要补软链**。

### 技能清单

| 技能（目录名） | 类型 | 用途 |
|---|---|---|
| `wires-change-workflow` | 流程 | **变更流程入口**：改代码前必加载，判定该不该走流程并指向权威正文 |
| `wires-grilling` | 流程 | 拷问式需求澄清：设计树 + 前线分轮提问，RFC 前置用它 |
| `wires-code-review` | 流程 | 提交前三轴评审：Standards / Spec / Fidelity，三轴并行 subagent |
| `wires-graph-domain` | 领域 | 领域模型与图引擎拆分指南（对照 kura / kura-wires） |
| `wires-rest-api` | 领域 | REST 层设计与端点映射（对照 WireRestService） |
| `wires-composer-vue` | 领域 | 前端图编辑器实现（对照 wires_composer.js + WiresPanelUi） |
| `dev-workflow-change-management` | 正文 | 变更流程权威正文（中文 `SUMMARY.zh.md` + 俄语上游 `SKILL.md`）；DSH 注册名沿用上游 `dev-workflow` |

前三个是**流程类**（不绑定某一层），中间三个是**领域类**，最后一个是流程正文库。做对应层级的开发前，先加载对应 skill。

### 变更流程（硬性触发）

任何要改代码的任务——新功能、修 bug、重构、迁移、接口/快照结构变更——开始干活前先加载
`wires-change-workflow`；它第一句就要求读权威正文：

```
read .dsh/skills/dev-workflow-change-management/SUMMARY.zh.md
```

`SUMMARY.zh.md` 是中文结构化正文（规则/阈值/清单齐全）；同目录俄语 `SKILL.md` 是上游原文（权威版本，
但项目特定信息以本文件为准），只在判定冲突或需要原始措辞时读。**流程正文只维护 SUMMARY 一处**，
入口文件不复写规则，避免两处漂移。

纯讨论、调研答疑、只改文档且不改变流程约定的任务不加载。

该 skill 的上游占位符 → 本仓库实际位置：

| 上游占位符 | kura-edge 中的实际位置 |
|---|---|
| `<REPO_ROOT>` | 仓库根 `/home/idea/IdeaProjects/kura-edge` |
| `PROJECT/rfc/NNN-title.md` | `change/rfc/NNN-<slug>.md`（目录按需创建） |
| `private/backlog/BL-NN.md` | `change/backlog/BL-NN.md`（当前为空，pre-flight 的待办审计因此跳过） |
| 构建 / 测试 / 验证命令 | 后端 `mvn clean verify`、单模块 `mvn -pl <module> test`；前端 `npm run lint && npm run test:unit && npm run build`；完整禁令清单见 `.dsh/rules/05-boundaries-and-verification.md` |
| 数据库迁移目录 | 无独立迁移目录：JPA 实体/Schema 变更放 `kura-edge-core`，默认 H2、可切 PostgreSQL，变更须在 RFC 里写明兼容性 |
| 提交信息 | `<type>(<scope>): <description>`，scope 用 Maven 模块短名，见「提交规范」一节 |
| 测试风格 | Gherkin 风格 `given*/when*/then*` 私有步骤方法，见 `.dsh/rules/06-testing.md` |

### 预检与 `gh`

上游预检里的 `gh pr list`（GitHub CLI）**可用**（2026-11-10 实测）：

- **版本/路径**：`gh 2.103.3` 在 `/home/idea/.local/bin/gh`；另有 `/home/idea/.local/bin/gh-agent`
  （用 agent 账号自己的 `GH_CONFIG_DIR`，但其脚本内部 `exec gh`，所以还要把 `.local/bin` 补进 PATH：
  `PATH=/home/idea/.local/bin:$PATH gh-agent ...`）。agent 的 `bash` 工具是非交互非登录 shell，
  **PATH 里没有 `.local/bin`**——`command -v gh` 会报未找到，直接用绝对路径 `/home/idea/.local/bin/gh`，
  别据此断言「未安装」。
- **凭据**：`gh auth status` 正常，已登录 `xuhaochun`（active，scopes 含 `repo`/`workflow`）与 `jifeng755`。
- **出网**：`gh api user`（REST）、`gh api graphql`（GraphQL）、`gh pr list` 均直连成功；仓库
  `xuhaochun/kura-edge`（private）可访问，`gh pr list --state all` 能列出历史 PR。

因此 pre-flight 的 `gh pr list` **应当真跑**：默认只看 open（`--limit` 自己定），要了解已合并历史加
`--state all`。只有再次遇到 PATH 之外的凭据/出网故障时才按 skill 的条件式逻辑跳过，并在报告里注明
「预检降级」及具体原因；**不要伪造 PR 列表。**
