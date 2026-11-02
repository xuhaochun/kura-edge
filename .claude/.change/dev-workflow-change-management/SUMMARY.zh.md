# dev-workflow 中文总结

> 本文是 `.claude/.change/dev-workflow-change-management/SKILL.md`（俄语原文，v0.5.0，
> 上游 `ymuromcev/claude-dev-workflow`）的结构化中文总结，**不是逐字翻译**：保留全部可执行规则、
> 阈值、清单与反模式，删掉原文的举例叙事与个人背景。判定冲突时以 `SKILL.md` 原文为准。
> 项目侧的路径/命令映射见仓库 `CLAUDE.md`「变更流程技能」一节。

## 1. 定位

- 面向**写代码**的任务：脚本、应用、MCP server、SaaS。
- **不适用**：产品类任务（Notion / Jira / Confluence 页面、文案、假设整理）、无产出的调研探索、
  单行改动（改错别字 / alias / 环境变量）。
- 触发：接到新的开发任务、修 bug、重构、迁移、架构变更、新子项目、"开始做这个功能"、
  "并行做"、或用户问"这任务属于哪一档"。

## 2. 核心不变量：BL（待办卡）状态流转 —— 每条 BL 单独执行

**开始动手前**（第一次改文件 / Edit / Write / 该任务的任何命令之前）：

1. 完整读 BL（frontmatter + Context + DoD）。
2. frontmatter 置 `status: in_progress`。
3. 补 `claimed_by: <branch>@<HH:MM>`、`claimed_at: <ISO 时间>`。

**收尾时**（DoD 通过且已提交之后）：`status: done` + `closed: <YYYY-MM-DD>` + `## Progress`
写结论（做了什么、commit / PR 链接）。

**失败模式**：先动手后补状态 = 违反不变量。立即停下、补状态、再继续。
禁止"最后一起补""关闭时再标"——`planned/open → in_progress` 必须发生在第一次改动**之前**。

**worktree 注意**：在 `.claude/worktrees/<name>/` 里工作时，BL 文件物理上只在主 checkout
（`private/backlog/`，被 gitignore）。此时用 Bash + 绝对路径（`sed -i` / `cat >>`）改主 checkout 的文件，
Edit/Write 会因 worktree 路径钩子失败。看到 "no private/" 不等于没有 BL——这是必然踩的坑。

放弃任务时：`in_progress → open`，清 `claimed_by/claimed_at`，在 `## Progress` 写明放弃原因与进度。

## 3. Pre-flight：分支审计 + 待办审计（在「步骤 0」之前）

解决两类复发问题：

- **旧分支陷阱**：在落后于 main 的分支上跑命令 → 结果不对 → 误判成代码 bug → 花几小时修不存在的问题。
- **并行会话共享一个待办**：2–3 个会话同时动 `backlog/` 与分支状态，不重读就会重复劳动或冲突。

| 时机 | 分支审计 | 待办审计 |
|---|---|---|
| 本会话第一次碰到代码仓 | 必做 | 必做 |
| 会话内换任务（关了一个 BL 接着下一个） | 必做 | 必做 |
| merge / push / 大规模预处理前 | 必做 | — |
| 每条消息 | 不 | 不 |

**分支审计**：`git rev-parse --abbrev-ref HEAD`、`git status -sb`、`git fetch origin main`、
`git rev-list --count HEAD..origin/main`、`git log --oneline HEAD..origin/main | head -10`、
`git worktree list`、`gh pr list --state open`（无 gh 时跳过）。

必须报告的情形：落后 main > 0 提交（说明落后几个、缺的提交在做什么、建议 merge/rebase/切 main，
**策略谈拢前不开工**）；工作区改的文件正被某个 open PR 改（可能重复）；存在多个活跃 worktree（列出）；
一切干净则一行 `branch: X, in sync with main`。

**待办审计**：按修改时间列 `backlog/*.md`、grep `^status: in_progress` 及 `claimed_by`。
必须报告：近 60 分钟内被**别的会话**改过的 BL；`in_progress` 且 `claimed_by` 不是本会话（"被占用，不接"）；
`in_progress` 超过 24h 无活动（疑似 stale claim，问用户是否重置）。

**认领机制**：会话身份 `<当前分支>@<HH:MM>`。认领前 grep `^claimed_by:`，已被他人新鲜认领就不接。
成本约 10 条命令、3 秒、500 token，抵一次幽灵 bug 或一次重复劳动。

**反模式**：拿分支不做审计就开工；不认领直接干（并行会话看不见，重复劳动）；任务之间依赖内存里的
待办缓存（间隔超 1 小时必须重读）；出现"结果不对/莫名测试失败/功能明明做了却不生效"时第一反应去查代码
——正确顺序是先查分支新鲜度与待办状态。

## 4. 步骤 0：分级

| 档 | 触发条件 | 流程 |
|---|---|---|
| **XS** | < 20 行、bug 修复、文本/配置改动、单文件 | DoD → 代码 → 冒烟测试 → 自查 `git diff` → 给用户看 → 提交 |
| **M** | 新功能、新脚本、2+ 文件、非平凡逻辑、新集成 | RFC → **用户批准** → 代码 + 测试 → lint → code-review agent → 给 diff + 摘要 → 提交 |
| **L** | 架构、迁移、安全、破坏性变更、新子项目、公共 API 变更 | 完整 RFC → **批准方案** → 分块实现（必要时 feature flag）→ code-review + `/security-review` → 总结 → **最终批准** → merge |

拿不准就往**高一档**取。一批任务（≥2 个独立）逐任务定档，同时触发步骤 0.7 的 PM 模式门。

## 5. 步骤 0.5：结果意象（user-level，先于代码）

在 RFC 之前、代码之前，先用**用户视角**写出"会变成什么样"并跟用户确认。对 M/L 必须做；
XS 仅在需求含糊或改变用户可见行为时做；纯 bug 修复/错别字/无 UX 变化的配置改动跳过。

模板四要素：`[现状] → [之后]`（用户视角）、用户做什么动作、会看到什么输出、哪些边界情况被处理、
**不包含**什么（留给后续/手工/另一个任务）；再写"放哪儿"（规则进哪个文件、代码进哪个模块、产物进哪个目录）。

存放位置：有 BL 就写进 `## Plan` 或专门的 `## Что хочется получить`（结果意象）小节；没有就发在聊天里
并拿到明确"OK"；两者都没有时，先提议建 BL 或先在聊天里定稿。

**规则：不在 BL 里、也不在聊天里被批准过的东西等于不存在。** `/compact` 之后的续写、隐含的"Step-N"、
"我想了想还需要 X"都不是计划来源。

## 6. 步骤 0.7：PM / dispatch 模式（可并行时切换角色）

不是独立流水线，而是叠在步骤 1–9 之上的角色变化：Claude 不自己写代码，做项目经理——拆解、
按单元确认结果意象、把独立单元分派给各自 worktree 里的子代理、并行监控、汇总、跑统一评审。
每个单元内部仍走同一套规则（M/L 写 RFC、冒烟测试、lint、评审）。

**自动开启的门（满足任一）**：≥2 个彼此无依赖的任务（档位无关，10 个 XS 也算）；一个 M/L 能切成
2+ 个独立单元（每个单元有自己的"用户会看到什么"，且互不阻塞，接口契约事先定死）；用户显式要求
（"并行做""起代理""一次全做完"）。

**不开启**：单个 XS（比直接改更慢）；依赖链（B 要 A 的结果）；范围还没谈清；单元之间文件高度重叠
（合并冲突吃掉收益）。

**门必须明说**：一句话讲清"N 个独立块，并行约等于最慢那个的耗时但更贵，串行更便宜且风格统一，要并行吗"，
由用户决定，不许默默切换。

**拆解（PM + 用户，user-level）**：先有整体结果意象，再切单元，各单元意象之和 = 整体意象。
每个单元只展示：用户会看到什么、**不含**什么、档位（XS/M/L）、是否独立或等哪个单元。
不展示类型、签名、函数名、数据结构、文件、代理之间怎么传值——这些进技术 brief，不给用户看。

边界情况自己讲出来：只切出 1 个单元 → 转回串行；某单元明显更大 → 说明要等它；拆不开 → 老实说串行并解释原因；
单元间有共享契约 → 用产品语言说明"两边数据形态必须一致，我来给代理定死"。

**dispatch 机制**：每个单元一个子代理 + 独立 worktree（`Agent` 工具 `isolation: "worktree"`），
一条消息里批量后台启动；harness 完成时通知，PM 不轮询。给代理的 brief 必须自足（它没有聊天上下文）：
产出物、技术契约（只由 PM 定）、单元 DoD、**不许碰**的边界、要自跑的冒烟测试、结果写回哪里。
**代理之间不通信**，一切问题过 PM；两个代理对不上契约 = 拆解错误。
给用户的进度只有每事件一行：启动 N 个、✅ 单元 X 完成（一句话结果）、⚠️ 单元 Y 返工（原因）。

**跑偏/卡住**：PM 不自己上手改（角色会糊），先带着"要什么 / 你做了什么 / 差在哪"重开单元；
第二次还不行就收回自己做并如实告知；worktree 长时间无响应直接关掉重开。

**集成**：按序把 worktree 合回主线；单元间冲突说明拆解没做好——手工修 + 写 `incidents.md`（blameless）；
跑**跨单元**整体冒烟；对合并后的 diff 跑多代理评审；M/L 单元要用户最终批准。

**预算**：并行数 = 单元数，用户说"起 20 个"但只有 5 个单元不会更快；单元 > 7 分批；token 敏感时先说明贵几倍再由用户决定。

**PM 不委派的**：整体结果意象、拆解与接口契约、最终集成与 DoD 校验、"要不要并行"的决定、与用户的沟通。
"代理是手，头与产品是 PM。"

## 7. 步骤 1：RFC（仅 M/L）

代码之前写短设计文档到 `rfc/NNN-title.md`（本项目 → `.claude/.change/rfc/NNN-<slug>.md`），章节：
问题（1–2 句）→ 备选方案 A/B → 选了哪个及原因 → **Identity & PII**（涉及用户数据时必填）→ 风险 →
验证计划（测试 / 手测场景 / 指标）。写完**等用户显式批准**才动代码。XS 不需要 RFC。

Identity & PII 段即使答案是 "N/A" 也要显式写出来：ID 形态与随机源、PII 字段与 schema 标记、
存储位置与加密/忽略策略、日志脱敏、能否硬删除（right-to-be-forgotten）、是否按 profile 隔离（multi-user-ready）。

## 8. 步骤 2：代码 + 测试

测试金字塔：**单元**（纯逻辑，无网络/磁盘，写最多）→ **集成**（外部 API，**必须 mock 网络**，
不然 flaky 且烧配额）→ **E2E/手测**（只覆盖关键生产路径，写成 RFC 里的清单，不急着自动化）。

**冒烟测试即使 XS 也必须写**：一个最简单的用例，证明主函数能被调用、不崩、返回预期类型。
两分钟成本，能拦住约 80% 的重构破坏。

## 9. 步骤 3–4：lint 与 pre-commit

lint 按需引入、装到子项目里、尽量零配置（JS: prettier + eslint；Python: ruff；bash 一次性工具靠人眼）。
pre-commit 钩子只在子项目已有测试或 lint 时才建：脚本放 `.githooks/pre-commit` 并提交进仓库 +
`git config core.hooksPath .githooks`；**第一版只警告不阻塞**，习惯后再切阻塞模式。
准备公开仓库时另加 secret-guard 钩子（拦 Notion token、OAuth secret、私钥、AWS key、作者姓名邮箱）。

## 10. 步骤 5：多代理评审 + 提交

- **M**：代码完成后派子代理拿 diff 评审，焦点＝可读性与命名、遗漏的边界、过度设计（为将来预留的抽象）、
  与 DoD 的符合度、硬编码密钥。致命项自己改，其余汇总给用户。
- **L**：额外跑 `/security-review`（注入、XSS、泄露、鉴权、危险默认值）、`/review`，公开功能再查 UX 边界
  （空状态、错误、断网）。
- **最终批准永远在用户手上**：M/L 没有明确 ok 不提交；XS 先给 diff，用户事先给过绿灯才提交。
- **提交即推送**：每次 `git commit` 后立即 `git push`（不再单独问），分支不存在就
  `git push -u origin <branch>`。例外：pre-commit 失败或测试红、明确标为 WIP 的中间提交、
  force push 到别人的分支或 main/master——这三种要显式批准。

## 11. 步骤 6：安全等级（增量升级）

| 等级 | 触发 | 加什么 |
|---|---|---|
| **S1 基础**（默认） | 本地脚本、MCP、个人工具 | L 档跑 `/security-review`；评审含密钥扫描；加新依赖前 `npm audit` / `pip-audit`；密钥不入库 |
| **S2 预生产** | 要对公网开放、出现 auth 或带数据的数据库 | 有 auth+数据的特性在 RFC 里做威胁建模、SAST（semgrep）、OWASP Top 10 清单、CI 里查密钥、依赖自动扫描 |
| **S3 生产有真实用户** | 公开 SaaS | 对 staging 跑 pentest 代理（拿 URL + 测试账号，按 OWASP Top 10 逐项 `payload → response → 判定`，按严重度出报告；critical/high 是发布阻塞，medium/low 进待办带期限）；定期执行；付费客户前做外部审计 |

pentest 代理**不替代**外部专业渗透、bug bounty、合规审计（SOC2/GDPR）；只在进入 S3 时再落地，不提前做。

## 12. 步骤 7：事件日志

生产出问题或第一次没做对，写进 `incidents.md`：日期 + 标题、**发生了什么**、**根因**、
**改了什么以免复发**（规则 / 测试 / 配置 / 重构）。**Blameless**：记原因不记人；
每次事故都应留下一个测试、一条 CLAUDE.md 规则或一处代码防护。

## 13. 步骤 8–9：CI 与文档

CI（GitHub Actions）只在第一个子项目对外产生真实用户时才建，最小集：PR 上跑测试、跑 lint、
CI 红则禁止 merge；之后按需增量。

文档＝系统行为的活契约，必须与代码同一个提交："回头再补文档" = 永远不补。

| 变化 | 更新什么 |
|---|---|
| 命令 / 公共函数 / 外部 API 行为 | `docs/SPEC.md` 对应的行为契约 |
| 数据流 / 子项目结构 / 新模块 | `ARCHITECTURE.md` |
| 产品级取舍（多选一） | 新增 ADR `docs/decisions/NNN-title.md`（上下文 / 决定 / 后果） |
| 用户可见变化（特性 / 破坏性 / 弃用 / 删除） | `CHANGELOG.md`（Keep a Changelog 格式） |
| 数据 schema 变化 | SPEC 对应段落（+ 迁移不平凡时写 incidents） |
| 安装 / onboarding / 启动方式 | `README.md` |
| 需要跨会话记住的规则 | `CLAUDE.md` |

文档体系（SPEC/ARCHITECTURE/CHANGELOG/ADR）尚不存在时，**先问用户**是否为本次改动建立，
不要默默创建；建了第一个产物之后它就按上表持续更新、不再反复问。
**不需要文档**：不改变行为的 XS 修复、语义不变的内部重构/测试/格式化、未进入公共契约的内部命名、
上线前 feature flag 下的实验。

## 14. backlog 梳理（groom，独立场景，不属于主流程）

触发：`/dev-workflow groom`、"梳理待办"、"过一遍 backlog"、"给任务估档"。
范围：仅本项目的 `backlog/BL-*.md`（本项目 → `.claude/.change/backlog/`），只**提建议**、逐项确认、
绝不自动批量 apply；不碰 Notion/Jira/GitHub Issues。

参数：无参数＝活跃项（open ∪ in_progress）；`BL-NN`＝单个；`--all`＝连 done/archived（仅 title/标签/closed 归一，
不改 status、不重估档）；`--priorities`＝额外建议 P0–P3（**默认不动优先级**）。

七类检测器：① 标题归一（错别字、半截标题、"fix the bug"、尾随空格、大小写不一致；保留原有前缀，
不改语义，拿不准就列为"要你的措辞"）；② **未梳理**（无 `## Plan`、DoD 缺失或空、Context 只有一两行）；
③ 档位评估（仅在已有 Plan+DoD 且 tier 空/可疑时，标准同步骤 0，拿不准取高档）；④ 状态不一致
（open 但 Progress 显示已完成 → 建议 done + 从 Progress 取日期；in_progress 静默 >14 天、open 静默 >30 天
→ 问"还做/暂停/归档"，不自行关闭；done/archived 缺 closed → 从 Progress / git / 今天取；非法状态值 → 标记）；
⑤ 空标签（从标题与 Context 关键词、refs 关联对象、相邻同类 BL 的标签取，不硬编）；⑥ 空 `created`
（`git log --diff-filter=A --follow` 取文件创建日，取不到用今天，绝不猜）；⑦ 优先级（仅带 `--priorities`，
按依赖、期限、业务影响给出 P0–P3 + 理由，逐条确认）。

**梳理 ≠ 替用户决策**：产品级岔路（做法的机制、触发时机、范围、已坏数据的清理策略、任何"选 A 还是 B"
且没有技术上的压倒性理由）一律进 `## Open questions`，按"选项 + 利弊 + Claude 倾向但仍等用户定"的格式列出；
可以自己定的只有实现细节（代码位置、函数/变量/测试名、所选方案内部的 if/else 结构、lint 归一）。

报告按**问题类型**分组（不按文件），0 命中不打印；全干净就一行"待办很干净，没什么可梳理的"。
apply 交互：`all`（先给汇总计划再要最终 ok）/ `select`（逐条 y/n）/ `by-group`（按类别整体应用）/ `none`。
"未梳理"那组走单独流程：逐个问是否现在梳理，是则问 2–3 个短问题后补 `## Plan` + DoD，否则在 Progress 留标记。
边界：frontmatter 解析失败 → 单列"损坏"并给最小修复；frontmatter 与正文冲突 → 归入"状态不一致"；
遗留文件名不改名（frontmatter 的 `id` 比文件名重要）；`done/archived` 默认整条跳过；
没有 backlog 目录就说"没有可梳理的单子"。

## 15. Definition of Done（报"完成"之前逐项自查）

- [ ] 结果意象（步骤 0.5）在代码之前已确认（M/L 必须；XS 若改变 UX 也必须）。
- [ ] PM 模式（步骤 0.7）如启用：拆解经用户同意；每个单元按自己的"用户会看到什么"验收；
      合并后跑了整体冒烟 + 评审；合并冲突已记入 `incidents.md`。
- [ ] 代码确实实现了要求（对照 TodoWrite 里的 DoD）。
- [ ] 冒烟测试（最低）或单元/集成测试（M/L）通过。
- [ ] lint 无错误（若已配置）。
- [ ] 亲自重读 `git diff`；跨文件改动一致。
- [ ] M/L 走了代码评审代理，致命项已修。
- [ ] 代码里没有密钥。
- [ ] 涉及用户数据时：ID 为不可反推的随机值（非 PII）；PII 字段在 schema 里有 `pii_class` 标记；
      日志/错误/栈里只有 ID，不含姓名邮箱；PII 与标识符分字段存放；PII 目录被 gitignore 或静态加密；
      可硬删除；所有数据按 `profile_id` 隔离。
- [ ] 行为变化按步骤 9 的触发表更新了文档。
- [ ] 任务来自正式待办时**已关闭 BL**：`status: done`、`closed:`、DoD 勾选、`## Progress` 写入
      commit/PR 链接（worktree 场景按第 2 节的注意事项处理）。

不达标就自己修，不要问。

## 16. 与用户的沟通语气

对方是产品角色、不是技术负责人：

- 讲 bug 与事故用业务语言与产品可见后果（评论重复、邮件漏发、状态错），不讲实现：可以出现
  cron / 日志 / 环境变量 / OAuth / 权限 / Docker，不要出现 `EACCES`、`writeFileSync`、`chown -R`、
  `uid 1000`、`Module._load` 这类。
- 讲岔路用产品影响表述（"选 A 则这问题不再复发但重部署 5 分钟；选 B 先扛着，复发再修"），
  不写 entrypoint / gosu 这类实现细节。
- 不要哄小孩：对方知道 cron、日志、环境变量是什么；"机器人""日记本"式比喻是噪音。
- 细节量：让对方明白"发生了什么、为什么重要"即可；要细节再给。
- 自己排障时看栈，回话时翻译成产品语言；只有用户点名问某个错误类才贴原始栈。

## 17. 本项目（kura-edge）适配要点

_- 路径替换：`private/backlog/` → `.claude/.change/backlog/`（当前为空，待办审计因而跳过）；
  `rfc/` → `.claude/.change/rfc/`；`docs/SPEC.md`、`ARCHITECTURE.md`、`CHANGELOG.md` 等项目文档尚未建立，
  按步骤 9 的规则**先问**再建。
- 测试栈替换原有 Node/Python 假设：后端 JUnit 5 + Mockito + AssertJ（Gherkin 风格 `given*/when*/then*`
  私有步骤方法，见 `.claude/rules/06-testing.md`），前端 Vitest + Vue Test Utils。
- 验证命令：后端 `mvn clean verify`、单模块 `mvn -pl <module> test`；前端
  `npm run lint && npm run test:unit && npm run build`；完整禁令清单见
  `.claude/rules/05-boundaries-and-verification.md`。
- 无独立迁移目录：JPA 实体/Schema 变更落在 `kura-edge-core`，默认 H2、可切 PostgreSQL，
  兼容性须在 RFC 里写明。
- 提交格式沿用 Conventional Commits，scope 用 Maven 模块短名。
- 本机未装 GitHub CLI：pre-flight 里的 `gh pr list` 跳过，并在报告里注明"预检降级"。
- 领域约束优先：任何涉及快照 JSON 字段名、多端口模型（`emitterPort`/`receiverPort`、
  `inputPortCount`/`outputPortCount`）的改动，按 `.claude/rules/08-additional-rules.md` 视为 L 档。_
