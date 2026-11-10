# RFC 009：发布服务器（8081）+ 设备拉网址更新

- 状态：已实现，待现场联调
- 档位：M（新增一个可执行模块 + 发布链路串接 + 契约上移到 api）
- 关联：RFC 006（后端 jar 热更新）、RFC 007（PostgreSQL 蓝绿）、RFC 008（设备拉清单）
- 上游参考：无

## 0. 问题

RFC 008 做完了"设备定时拉清单"，但清单和 jar **放哪儿**没人管：

- 现场只有 Windows 机器和 JDK，装 nginx 反而更麻烦；
- 手写 `feed.json` 最容易出的错是"发了新 jar 忘了改清单里的 sha256"，表现是设备一直 400004；
- 需要一个能被 `build-desktop.sh` 直接喂、又能立刻对设备提供服务的发布点。

## 1. 方案：项目里多一个 8081 的发布服务

新增 Maven 模块 `kura-edge-release-server`（Spring Boot，8081），只做一件事：**把 `releases/` 目录
里现有的 jar 变成设备要的 `feed.json` + 下载地址**。

```
kura-edge-release-server/releases/            ← build-desktop.sh 自动投放
  kura-edge-1.0.3.jar
        │  启动时/每次请求扫描
        ▼
   ReleaseCatalog（解析文件名 → 版本、算 sha256 并缓存）
        │
        ├── GET /feed.json          → {protocol, latest:{version,url,sha256,size,publishedAt}}
        ├── GET /kura-edge-1.0.3.jar → jar 本体（流式，带 Content-Length）
        ├── GET /                → 人看的首页：有哪些版本 + 设备怎么配
        └── GET /healthz          → ok
```

设备侧一行配置：

```bash
KURA_EDGE_UPDATE_FEED=http://192.168.31.40:8081/feed.json
```

之后每 5 分钟拉一次，发现比本机新就下载、校验、走蓝绿热更新（RFC 007）。

### 1.1 关键设计

| 决定 | 理由 |
|---|---|
| **文件名即版本**，只认 `kura-edge-<版本>.jar`，版本号 `[0-9A-Za-z][0-9A-Za-z._-]{0,31}` | 与设备侧 `BackendUpdateService.VERSION_PATTERN`、外壳 `check_version` 同一套规则，避免"清单里有、设备拒收" |
| 清单里的 `url` 用**相对路径** | 设备按清单自身地址解析；服务器换 IP/域名/端口都不用改清单 |
| sha256 按（路径,大小,mtime）缓存 | 60 MB 的 jar 每次请求都重算太浪费；同名覆盖会自动失效 |
| 没有上传接口 | jar 由构建脚本或人工放进目录。少一个写入口就少一类现场事故 |
| 不依赖 core/components/rest | 它是运维基础设施，故障域必须和业务服务分开；只借 api 里的两个共享契约 |

### 1.2 契约上移到 `kura-edge-api`

新增包 `com.kura.wires.update`：

| 类 | 原来在哪 | 为什么搬 |
|---|---|---|
| `VersionComparator` | `kura-edge-rest` | 发布侧要判断"目录里哪个 jar 最新"、设备侧要判断"要不要装"，**必须是同一套规则**（`1.0.10 > 1.0.9` 靠字符串比会反） |
| `FeedManifestDto` | `kura-edge-rest` | 清单是两端的共享格式，字段名各写一份迟早漂 |

api 模块只依赖 JDK，搬过去不会给发布服务器带进 Spring/JPA。

### 1.3 架构规则的例外

rules/04 原本规定"`rest` 是唯一启动模块、唯一允许出现 Controller 的模块"。本 RFC 显式开一个口子：

- `kura-edge-release-server` 是**第二个可执行模块**，但**不是业务装配**：只能依赖 `api`，
  禁止依赖 core/components/rest，禁止读写数据库，禁止出现任何图/组件逻辑；
- ArchUnit 的 `controllersOnlyLiveInRestControllerPackage` 放行 `com.kura.wires.release..`；
- rules/03、rules/04、CLAUDE.md 已同步这条例外。**再新增可执行模块必须先改规则**。

## 2. 发布链路串接

`build-desktop.sh` 的"发布可热更新的 jar"步骤多两件事：

1. 把 jar 拷进 `kura-edge-release-server/releases/`（`KURA_RELEASE_DIR` 可覆盖）；
2. `--jar-only` 的提示改成两条路径：**【A】用本项目的发布服务器**（起 8081 + 配 `KURA_EDGE_UPDATE_FEED`）/
   **【B】自己拿 nginx/静态目录发**（复制 `dist/*` 到同一目录）。

`dist/feed.json` 仍然生成，给走 nginx 的现场用；发布服务器的清单由它自己扫目录生成，两者不会打架。

## 3. DoD

- [x] 模块可独立打包运行：`java -jar target/kura-edge-release-server-*.jar` → 8081
- [x] `GET /feed.json` 返回的 sha256 与 jar 实际摘要一致（实测与 `dist/kura-edge-1.0.3.jar` 相同）
- [x] `GET /kura-edge-<版本>.jar` 返回可用的 zip（`PK\x03\x04`）
- [x] 空目录：`/feed.json` 404 且首页给出"把 jar 放进来"的提示，不抛异常
- [x] `mvn -o verify`：新模块 11 项测试全过、覆盖率 ≥ 0.80、checkstyle 通过
- [x] `releases/` 已放入当前构建的 `kura-edge-1.0.3.jar`
- [x] 端到端实测：设备（current=1.0.3）→ 8081 清单（1.0.4）→ 下载 60 MB → sha256 校验 → 写出
      `pending.json` → 单机模式自行退出等外壳重启
- [ ] 现场联调：Windows 上位机 + 真实网段（`192.168.31.x`）拉一次、升一次

## 4. 安全

- 只读，无上传/无删除；文件名经白名单与"必须存在于索引"两道校验，天然挡掉路径穿越；
- 监听 `0.0.0.0`（内网发布点，设备要能访问）；jar 内容就是应用本身，不含用户数据与凭据；
- 若要限制来源，用主机防火墙或前置网关，不在应用里做 ACL（避免又造一套权限体系）。
