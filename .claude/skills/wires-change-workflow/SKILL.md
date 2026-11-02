---
name: wires-change-workflow
description: kura-edge 代码变更的强制流程入口。任何新功能、修 bug、重构、迁移、接口/DTO/快照结构变更、依赖或分层调整，动手前先加载本技能。
---

# kura-edge 变更流程入口

本技能是**薄入口**，只做三件事：判断该不该走流程、把权威正文递给你、标出不可跳过的门。
**规则正文只维护 `.claude/.change/dev-workflow-change-management/SUMMARY.zh.md` 一处**，本文件不复制它；
两处说法冲突时以 SUMMARY 为准，并回来修正本文件。

## 第一步：读权威正文（不可跳过）

```
read .claude/.change/dev-workflow-change-management/SUMMARY.zh.md
```

283 行中文，规则、阈值、清单齐全。俄语原文 `SKILL.md` 只在需要原始措辞或判定冲突时读
（它是权威版本，但项目特定信息以 `CLAUDE.md` 为准）。

## 适用范围

**必须走流程**：

- 新增功能、修 bug、重构、迁移
- 接口 / DTO / 快照 JSON 结构变化（上游兼容性是硬约束）
- 触碰 `.claude/rules/` 任何一条硬约束的改动：分层、依赖、错误码、安全、命名
- 修改 `CLAUDE.md` 的「硬约束清单」或 `rules/` 原文

**不必走流程**：纯讨论、调研答疑、读代码解释、只改文档且不改变流程约定。

拿不准时按「要走」处理，成本只是一次 read，漏掉的成本是返工。

## 三条不可跳过的门

1. **Pre-flight**：开工前确认分支状态。本机**没有 `gh`**，`gh pr list` 一步按 skill 自身的条件式逻辑跳过，
   并在给用户的报告里注明「预检降级」；不要为凑步骤去装 `gh`，更不要伪造 PR 列表。
2. **XS 也要冒烟测试**：XS/M/L 分级只决定要不要写 RFC，**不决定要不要测试**。
3. **DoD 自查后才报「完成」**：后端 `mvn clean verify` 或前端
   `npm run lint && npm run type-check && npm run test:unit && npm run build` 必须真跑过；
   没跑的项要明说没跑，不能含糊地报完成。

## 与其他技能的衔接

| 处境 | 下一步 |
|---|---|
| 需求还没定清，或方案有多个岔路 | `wires-grilling` —— 问到设计树无未决分支，产出的决策直接进 RFC |
| M/L 级，需要先写 RFC | `.claude/.change/rfc/NNN-<slug>.md`，编号顺延，格式参考 `001-timer-fifo-scheduling.md` |
| 代码写完，提交前 | `wires-code-review` —— Standards / Spec 两轴并行评审 |
| 要动领域模型、图引擎 | `wires-graph-domain` |
| 要动 Controller / DTO / 错误码 | `wires-rest-api` |
| 要动前端画布 | `wires-composer-vue` |

## 占位符映射

SUMMARY 里的 `<REPO_ROOT>`、`PROJECT/rfc/NNN-title.md`、验收命令、提交信息格式等占位符，
对应到本仓库的实际位置见 `CLAUDE.md` 的「变更流程技能」一节表格，**表在那边，这里不重复维护**。
