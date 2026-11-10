# RFC 010：把设备的更新源收敛到发布服务器（一键升级）

- 状态：已实现，待现场联调
- 档位：M（配置项 + 客户端抽取 + DTO 放宽 + 前端主流程改写）
- 关联：RFC 006（jar 热更新）、RFC 007（蓝绿）、RFC 008（拉清单）、RFC 009（发布服务器）
- 上游参考：无

## 0. 问题

RFC 008/009 之后，设备仍然要**手写一个完整清单地址**：

```bash
KURA_EDGE_UPDATE_FEED=http://192.168.31.40:8081/feed.json
```

而且页面上的「升级」还是让人**填 jar 链接**。可现场已经有发布服务器了，它既知道最新版本、
也知道 jar 地址和 sha256——让运维再抄一遍链接纯属自找麻烦（抄错一次就是 400004 或装错版本）。

## 1. 方案：更新源只剩一个概念——发布服务器地址

### 1.1 配置

```yaml
kura.update:
  # 首选：发布服务器地址，设备按 <地址>/feed.json 拉清单、按清单里的相对地址下 jar
  release-server-url: ${KURA_RELEASE_SERVER_URL:http://192.168.31.21:8081}
  # 可选：显式清单地址（nginx/静态目录发版），两者都配时以它为准
  feed-url: ${KURA_EDGE_UPDATE_FEED:}
```

- 只配 `release-server-url` 就够（末尾斜杠自动吃掉，避免拼出 `//feed.json`）；
- `feed-url` 保留给"不用发布服务器"的现场，语义是更精确的意图，所以优先。

### 1.2 请求侧：`POST /api/v1/system/update` 的 `url` 变成可选

| 请求体 | 行为 |
|---|---|
| `{"url":"https://…/kura-edge-1.0.4.jar"}` | 与以前完全一致：用给的地址，摘要可留空 |
| `{"version":"1.0.4"}` | 到清单里取：地址 = `<清单地址>` 解析清单里的相对 `url`，**sha256 用清单里的** |
| `{}` | 同上，版本取清单的 `latest`（"升到最新版"） |
| `{"version":"1.0.2"}` 而清单最新是 1.0.4 | 400004：清单是单指针，只能装最新版；要装别的版本请给 jar 地址 |

关键点：一旦走清单，**摘要必然存在且来自服务器**，比手填更不容易错，也堵住了"请求里不给摘要就只靠 HTTPS"的漏洞。

### 1.3 前端：主按钮改成「升级到 1.0.4」

```
更新源：http://192.168.31.21:8081/feed.json
自动更新：已开启，每 1 分钟检查一次；清单里最新是 1.0.4，上次检查 21:40
[取消]  [升级到 1.0.4]        ← 一键，不再填网址
```

- 只要清单里看到比本机新的版本，主按钮就是「升级到 X」，点它发 `{version:"1.0.4"}`；
- 手填网址的输入框保留（折叠语义：这是"静态目录/临时指定"的兜底），输入框为空时不再阻塞主按钮；
- 更新源地址与自动更新状态直接显示在弹窗里，现场不用去猜配的是哪个地址。

## 2. 实现

| 文件 | 变化 |
|---|---|
| `kura-edge-rest/.../service/ReleaseServerClient.java` | **新增**：清单地址推导（`release-server-url` → `/feed.json`）、拉清单 + 协议校验、相对地址解析、按版本取条目（不一致就拒） |
| `.../service/UpdateFeedService.java` | 改用 `ReleaseServerClient`；`enabled()` 以"配置了更新源"为准；状态里多报 `serverUrl` |
| `.../service/BackendUpdateService.java` | 请求不带 `url` 时用 `ReleaseServerClient.require(version)` 解析地址/摘要/版本（清单路径下摘要必填） |
| `.../dto/UpdateRequestDto.java` | `url` 从 `@NotBlank` 改为可选（仅允许空或 http/https） |
| `.../dto/FeedStatusDto.java` | 新增 `serverUrl` 字段 |
| `application.yml` | 新增 `kura.update.release-server-url`，`feed-url` 默认留空（推导） |
| `frontend/src/components/system/UpgradeDialog.vue` | 一键升级主流程 + 更新源展示；手填网址降级为兜底 |
| `desktop/scripts/build-desktop.sh` | 提示改为"只配 `KURA_RELEASE_SERVER_URL`" |

## 3. DoD

- [x] `ReleaseServerClientTest`（8 项）：地址推导/显式清单优先/未配置即报错/协议不支持/缺 latest/
      相对地址解析/按版本取条目及不一致拒绝
- [x] `BackendUpdateServiceTest`（20 项，含 4 项新增）：不带 url 走清单成功（地址与摘要都来自清单）、
      未配置更新源报 409004、版本与服务器最新不一致报 400004、清单缺 sha256 拒绝
- [x] `UpdateFeedServiceTest`（8 项）改用客户端后全绿
- [x] `SystemUpdateControllerTest`（20 项，含 2 项新增）：`{"version":"1.0.4"}` 受理；
      `ftp://` 地址 400001
- [x] 前端 `UpgradeDialog` 8 项挂载测试（含一键升级只发 `{version}`）
- [x] `mvn verify` 全绿、JaCoCo 门槛未破
- [ ] 现场联调：设备只配 `KURA_RELEASE_SERVER_URL` → 页面出现「升级到 X」→ 一键升级走蓝绿

## 4. 兼容性

- 已经配了 `KURA_EDGE_UPDATE_FEED` 的设备**无需改动**：显式清单地址优先，行为与 RFC 008 完全一致；
- 手工/脚本用 `curl` 传 `url` 的老用法不变（只是 `url` 不再必填）；
- `POST /update` 的鉴权、蓝绿移交、`pending.json` 协议全部未变。
