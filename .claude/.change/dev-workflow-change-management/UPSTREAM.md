# 上游来源（只读参考，不要手改 SKILL.md）

| 项目 | 值 |
|---|---|
| 仓库 | https://github.com/ymuromcev/claude-dev-workflow |
| 提交 | `cf56a87c9474469ab44c276a841f9e8b9ec3d44e`（2026-05-25，v0.5.0） |
| 上游 skill 名 | `dev-workflow`（本目录按用途重命名为 `dev-workflow-change-management`） |
| 文件 | `SKILL.md`（逐字复制，sha256 `8dbece5c…b3b0c9`）、`LICENSE` |
| 本项目派生 | `SUMMARY.zh.md` —— 上述 SKILL.md 的中文结构化总结（保留全部规则/阈值/清单，删去举例叙事），非逐字翻译 |
| 许可 | 见同目录 LICENSE |
| 拉取时间 | 2026-10-17 |

## 为什么放在 `.claude/.change/` 而不是 `.claude/skills/`

`.claude/skills/<name>/SKILL.md` 会被 Claude Code 在会话启动时自动注册成可调用 skill；
`.claude/.change/` 不在发现路径里，所以它不会变成 `/dev-workflow-change-management`，
而是由 `CLAUDE.md` 明确要求"做代码变更前先 read 这个文件"来生效。这样保留了上游原文，
同时把它当项目内的变更流程规范使用（不需要改 SKILL.md 正文）。

## 上游语言

SKILL.md 正文为俄语（作者母语），文档结构：Pre-flight → 分级（XS/M/L）→ 结果意象 →
PM/dispatch 模式 → RFC → 代码+测试 → lint → pre-commit → 多 agent 评审 → 安全等级
（S1/S2/S3）→ 事件日志 → CI → 文档 → DoD 检查表。项目侧的路径/命令映射写在
`CLAUDE.md` 的「变更流程技能」一节，SKILL.md 内不写死本项目信息。

## 更新方式

```bash
git clone --depth 1 https://github.com/ymuromcev/claude-dev-workflow.git /tmp/cdw
cp /tmp/cdw/SKILL.md .claude/.change/dev-workflow-change-management/SKILL.md
```

更新后同步修改上表的提交号与 sha256。
