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

## 放在 `.dsh/skills/` 下的原因与用法

本目录位于 `.dsh/skills/dev-workflow-change-management/`，在 DSH 的技能发现路径（`.dsh/skills`）里，
因此 `SKILL.md` 会被自动注册成可调用 skill。注册名沿用 frontmatter 的 `dev-workflow`（上游原文，未改）。

项目内的入口不是直接调它，而是薄入口 `.dsh/skills/wires-change-workflow`：入口按 description 在
「要改代码」时被加载，正文第一句要求先读同目录 `SUMMARY.zh.md`（中文正文）；只有判定冲突或需要
原文措辞时才读俄语 `SKILL.md`。这样保留上游原文，同时项目侧规则只维护 SUMMARY 一份。

## 上游语言

SKILL.md 正文为俄语（作者母语），文档结构：Pre-flight → 分级（XS/M/L）→ 结果意象 →
PM/dispatch 模式 → RFC → 代码+测试 → lint → pre-commit → 多 agent 评审 → 安全等级
（S1/S2/S3）→ 事件日志 → CI → 文档 → DoD 检查表。项目侧的路径/命令映射写在
`CLAUDE.md` 的「AI 协作与变更流程」一节，SKILL.md 内不写死本项目信息。

## 更新方式

```bash
git clone --depth 1 https://github.com/ymuromcev/claude-dev-workflow.git /tmp/cdw
cp /tmp/cdw/SKILL.md .dsh/skills/dev-workflow-change-management/SKILL.md
```

更新后同步修改上表的提交号与 sha256。
