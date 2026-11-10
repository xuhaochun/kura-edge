# RFC 008：自动更新接口（上位机主动拉清单）

- 状态：已实现，待 Windows 实机冒烟
- 档位：M（新增一个服务 + 两个端点 + 发布侧清单 + 前端状态展示）
- 关联：RFC 006（后端 jar 热更新）、RFC 007（PostgreSQL 蓝绿零中断）
- 上游参考：无

## 0. 问题

现场的部署形态是**上位机只能单向访问服务器**：设备能连出去，服务器连不回设备。于是：

- RFC 006/007 的热更新都要求"有人对设备发一次请求"（页面按钮或 `curl`）——**服务器侧推不动**，
  每次发布都得逐台设备点一遍；
- 反过来让服务器 `POST` 设备（开放入站端口）既过不了防火墙，也把攻击面放大一整圈。

要的是一个**由设备主动发起**的更新接口。

## 1. 方案：单指针清单 + 定时拉取

```
服务器（静态目录）                         上位机（exe 内嵌后端）
  feed.json      ← 发布脚本生成，指向最新 jar
  kura-edge-1.0.4.jar
                       ①GET feed.json（每 5 分钟，单向出网）
                 ←──── ②版本比本机新？→ 下载 jar + 校验 sha256
                       ③写 staging/pending.json（与 /update 同一条路径）
                       ④桌面外壳接管：蓝绿交接（RFC 007）或就地重启
```

### 1.1 契约

`feed.json`（`build-desktop.sh` 的"发布可热更新的 jar"步骤自动生成）：

```json
{
  "protocol": 1,
  "latest": {
    "version": "1.0.4",
    "url": "kura-edge-1.0.4.jar",
    "sha256": "…64 位…",
    "size": 60126433,
    "publishedAt": "2026-11-10T10:34:24Z"
  }
}
```

- 单指针：只描述"当前应该跑哪个版本"，设备不需要历史列表；
- `url` 允许相对路径，按**清单自身地址**解析——整目录一起上传即可（`http://server/releases/feed.json`
  + 同目录的 jar）；
- `protocol` 不匹配按"清单非法"处理，避免未来格式变更被老设备误读；
- 后端配置：`kura.update.feed-url`（环境变量 `KURA_EDGE_UPDATE_FEED`），留空 = 不启用。

### 1.2 判定规则（全部是"宁可不动"）

| 情况 | 动作 | 为什么 |
|---|---|---|
| 清单版本不新于本机 | 不动 | 只升不降；回退走页面手动升级 |
| 本机版本是 `dev`/`unknown` | 不动 | 别把 `mvn spring-boot:run` 的开发机刷成发布版 |
| `staging/pending.json` 还在 | 跳过 | 外壳没处理完，覆盖它会打断正在进行的更新 |
| 同一版本已提交过 | 跳过 | 蓝绿模式下旧实例会继续存活一会儿，不跳过就会重复提交 |
| 该版本提交失败 / 外壳回滚过 | 冷却 `retry-after-ms`（默认 30 min） | 一台"装不上"的设备不能每 5 分钟下载 60 MB 再折腾一次 |
| 非桌面运行时（无 `staging/`） | 不启动轮询 | 没有地方写 `pending.json`，轮询无意义 |

### 1.3 与现有流程的关系

**没有新的落地路径**：清单轮询最终调用 `BackendUpdateService.requestUpdate(...)`，与页面按钮、
`curl` 走完全相同的下载校验、`pending.json`、蓝绿/就地重启语义。这样才不会出现
"自动更新能装、手动更新装不上"这类分叉。

### 1.4 状态与可观测性

- 端点：`GET /api/v1/system/feed`（状态）、`POST /api/v1/system/feed/check`（立即检查一次）；
- 状态落盘：`staging/feed-state.json`（后端独占写，重启后不忘记"这个版本装不上"）；
- 页面「升级」弹窗里显示一行：自动更新是否开启、间隔、清单里的版本、上次检查时间，并可「立即检查」。

## 2. 安全

- 清单与 jar 都只接受 `http/https`（复用 `HttpArtifactFetcher.parseUri`），`file:` 一律拒；
- 清单限长 256 KiB、超时 10 s（避免把轮询线程挂在一台半死的文件服务器上）；
- **清单里的 version 会拼进落地文件名**，因此在下载之前用与 `/update` 相同的白名单正则校验
  （`^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$`），否则清单被篡改就等于路径穿越；
- jar 的 sha256 来自清单，下载侧边下边算并比对——校验强度取决于清单本身的可信度（HTTPS 或内网）；
- 不引入任何入站端口：整条链路都是设备主动出网。

## 3. DoD

- [x] `mvn verify` 全绿（新增 `UpdateFeedService` / `VersionComparator` 覆盖）
- [x] 单测覆盖：新版本落包、同/旧版本不动、非法版本不下载、pending 存在跳过、回滚冷却、
      摘要不符记失败且不重复下载、未配置清单不启用、清单拉取失败只记状态不抛异常
- [x] 控制器测试：401/403、状态查询、立即检查
- [x] 发布侧：`build-desktop.sh` 生成 `dist/feed.json`（`--jar-only` 与完整模式都生成）并打印用法
- [x] 前端：弹窗显示自动更新状态 + 立即检查（7 项挂载测试）
- [x] 文档：本 RFC、`desktop/README.md`、`rules/07`、`CLAUDE.md`
- [ ] Windows 实机冒烟：改清单版本 → 5 分钟内自动升级；改错 sha256 → 冷却且设备不受影响

## 4. 实现与初稿的差异

1. 初稿想用 Spring `@Scheduled`，实际用单线程 `ScheduledExecutorService`（与 `BackendUpdateService`、
   `GraphLeadership` 一致），这样单测可以直接调 `pollQuietly()`，不必 `@EnableScheduling` 也不必等定时器。
2. 初稿只记"已提交版本"，实现里补了 `failedVersion/failedAt` 与读取 `app/apply-result.json` 的
   `rolled_back` 判定：真正需要冷却的是"外壳装过一次又回滚"，光记提交是不够的。
3. 初稿打算允许降级（清单版本 != 本机就升），改成**只升不降** + 页面手动回退：否则服务器上一次
   误操作会把全网设备集体降级。
