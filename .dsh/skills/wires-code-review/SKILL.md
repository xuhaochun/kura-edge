---
name: wires-code-review
description: 提交前 / PR 前的三轴评审：Standards（守不守本项目规范）、Spec（是不是做对了要的东西）、Fidelity（是否忠实于上游 Kura 语义）。三轴派并行 subagent，各自独立报告，禁止跨轴排名。
---

# 三轴评审

固定点（fixed point）= 用户说的那个 ref（SHA / 分支 / tag / `HEAD~3` / `main`）。
没指定就问，不要自己猜。

```bash
git rev-parse <fixed-point>                 # 先确认这个 ref 能解析
git diff <fixed-point>...HEAD               # 三点，与 merge-base 比较
git log <fixed-point>..HEAD --oneline       # 顺带看提交意图
```

差集为空或 ref 解析失败 → **在这里就报错退出**，不要带着坏 ref 去派 subagent。

## 为什么要三轴

一个改动可以过一轴挂另一轴，合并报告会让强的那轴掩盖弱的：

- 完全符合规范，但做错了需求 → **Standards 过，Spec 挂**
- 精确实现需求，但违反分层/命名 → **Spec 过，Standards 挂**
- 两者都对，但移植时把上游的行为改了（边界条件、顺序、默认值）→ **前两轴都过，Fidelity 挂**

第三轴是本项目特有的：kura-edge 是上游 Kura 的重写，最大的风险不是写错代码，而是**照着写但语义漂了**。
这类问题不违反任何本项目规范，也不违背 RFC 文字，只有对着上游源码才能发现。

## 派三个并行 subagent

每个 subagent **起全新上下文**（不要 fork 当前会话，fork 会继承上下文，正是要避开的东西），
分别只拿到自己的那轴材料，互不污染。用 `subagent` 工具并行发出。

### 轴 1：Standards

材料：`git diff` + `CLAUDE.md` 的「硬约束清单」+ `.dsh/rules/` 下的 `01`–`08` + `checkstyle.xml`。

**先排除工具已经强制的项**：ArchUnit 分层、JaCoCo 覆盖率、Checkstyle 行宽、ESLint —— 这些
`mvn verify` / `npm run lint` 会自己失败，人工评审再报一遍是噪音。只确认"这些命令确实跑过"。

人工要查的是自动化抓不到的：

- 公共 API（api 接口、对外 DTO）是否有中文 Javadoc / TSDoc；TODO 是否带责任人
- `ErrorCode` 是复用还是随手新造；码值是否归位（参数错 400，拓扑错 409）
- 密码字段出 DTO 是否脱敏
- Gherkin 步骤命名与正反路径覆盖（不是覆盖率数字，是场景是否讲得通）
- 移植类是否保留了上游类名与核心方法名（`WireEnvelope`、`get()` / `update()` / `delete()`）
- 快照 JSON 是否只追加、未改名
- Controller 是否只做转换 + 校验 + 授权 + 调 Service（超 ~30 行说明逻辑没下沉）

再叠加一组固定坏味道（每条都是**带标签的启发式**，不是硬违规；仓库明文规范永远压过它）：
Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、
Repeated Switches、Shotgun Surgery、Speculative Generality、Message Chains、Middle Man、Refused Bequest。
格式统一为「可能的 Feature Envy」这种措辞。

### 轴 2：Spec

材料：`git diff` + `git log`。按顺序找规格来源：

1. 提交信息里的 `#123` / `Closes #45`
2. `change/rfc/NNN-*.md` 里与本分支匹配的那篇
3. `docs/` 下的设计文档
4. 都找不到 → 问用户规格在哪；用户说没有 → 本轴跳过，报告里写「无规格可比对」

查的是：**改动是否忠实实现了 RFC / issue 的要求**，有没有做需求之外的东西（Speculative Generality），
有没有漏掉 RFC 里写明但代码里没做的条目。逐条对照 RFC 的决策点，不要凭印象。

### 轴 3：Fidelity（上游语义保真）

材料：`git diff` + 对应的上游源码（只读）：

- `/home/idea/IdeaProjects/kura/kura/org.eclipse.kura.api/.../wire/`
- `/home/idea/IdeaProjects/kura-wires/bundles/org.eclipse.kura.wire.provider/.../internal/wire/`

逐项比对**行为**而非签名：

- 图更新的执行顺序（stop → reconnect → start）与"只重建变化的连线与组件"的增量语义
- 端口边界校验的方向和边界值（`emitterPort ≤ outputPortCount` 还是 `<`；0 基还是 1 基）
- 环检测的判定范围（自环算不算、多端口是否逐端口判断）
- 快照字段的默认值与缺省行为（字段缺失时上游怎么处理）
- `WireRecord` 不可变性与 `Scalar` 类型约束是否保留

**发现"上游这么做、我们那么做"时必须给出上游文件与行号**，光说"感觉不一样"不算发现。
若差异是 RFC 里明确决定要改的，标为「已决偏离」而非缺陷。

## 汇总

三份报告分别放在 `## Standards` / `## Spec` / `## Fidelity` 三个标题下，**原样或轻整理，不要合并、不要重排**。

结尾只给一行统计：每轴发现数 + **各轴内部最严重的那条**。
**禁止跨轴挑一个总冠军** —— 那正是分离三轴要防止的重排。

## 反模式

- 把三轴塞进一个 subagent 跑完：上下文互相污染，Spec 的宽容会盖掉 Standards 的尖锐。
- 拿覆盖率数字当 Standards 结论：覆盖率是下限，测试写得对不对是另一件事。
- 用"看起来没问题"结案：每条发现要能指到文件与行号。
- 只审 diff 不看 RFC：Spec 轴会退化成"代码写得挺合理"。
