# kura-edge-release-server（发布服务器，8081）

给现场设备**发版本**用的小服务：把 jar 放进来，它替你把 `feed.json` 清单算好并通过 HTTP 提供出去，
设备那边只要一个环境变量就能自动升级（配合 RFC 006/007/008 的热更新链路）。

## 为什么要有它

设备能做的只是"定时 GET 一个清单"（上位机只能单向访问服务器）。清单里要求版本号、sha256、大小
三者都与 jar 对上，手写最容易出的错就是**发了新 jar 忘了改清单**——于是设备永远升不上去。
这个服务直接扫目录，最新版本、摘要、大小都由文件本身算出来。

## 起服务

```bash
# 1) 把要发的 jar 放进 releases/（build-desktop.sh 会自动拷，也可以手工丢）
ls kura-edge-release-server/releases/
#   kura-edge-1.0.3.jar

# 2) 打一次包（第一次需要）
mvn -o -pl kura-edge-release-server -am -Dmaven.test.skip=true package

# 3) 起（默认 8081，绑 0.0.0.0）
cd kura-edge-release-server
java -jar target/kura-edge-release-server-1.0-SNAPSHOT.jar
```

### 在 IDEA 里运行

**先让 IDEA 认得这个模块**：Maven 面板 → ⟳ `Reload All Maven Projects`
（或右键本目录的 `pom.xml` → `Add as Maven Project`）。之后 `ReleaseServerApplication` 的 `main`
左边会出现 Run 图标，右键也能 Run。

> 为什么一开始右键没有 Run：这个模块是后加的，IDEA 的 Maven 模型里只有 api/core/components/rest 四个
> 模块（日志里那句 `Maven project virtual file is null for .../kura-edge-release-server/pom.xml` 就是
> 当时导入时文件还没进 VFS，模块被静默跳过）。文件不属于任何 module，IDEA 就不会提供 Run。

不想等重导入，也可以用仓库里带的 Maven 运行配置 `.run/release-server (8081).run.xml`
（出现在 Run 下拉框里，只依赖 pom，导入前后都能跑），或命令行：

```bash
mvn -o -f kura-edge-release-server/pom.xml spring-boot:run
```

两条坑：

- **别在项目根目录跑 `mvn spring-boot:run`**：聚合根模块没有 main class，会报
  `Unable to find a suitable main class`；
- `mvn -pl kura-edge-release-server spring-boot:run`（不带 `-am`）用的是 `~/.m2` 里的
  `kura-edge-api`，旧包没有 `com.kura.wires.update` 就会编译失败——先
  `mvn -o -pl kura-edge-api -am -DskipTests install` 或直接加 `-am`。

可选参数：

| 参数 / 环境变量 | 默认 | 说明 |
|---|---|---|
| `--server.port` / `KURA_RELEASE_PORT` | 8081 | 监听端口 |
| `--kura.release.dir` / `KURA_RELEASE_DIR` | `releases` | jar 目录（相对路径：工作目录 → 模块根） |
| `--kura.release.base-url` / `KURA_RELEASE_BASE_URL` | 空 | 对外基地址，只用于首页示例文案 |

**关于 `releases/` 目录**：`--kura.release.dir` 给相对路径时会先按工作目录找，找不到再回退到"代码同级的
模块目录"。所以 `java -jar target/…jar`（cwd=模块目录）和**在 IDEA 里直接 Run**（cwd=项目根）都能找到
`kura-edge-release-server/releases/`，启动日志里会打印最终用的是哪个目录。

## 端点

| 路径 | 说明 |
|---|---|
| `GET /feed.json` | 设备用的清单：版本、相对地址、sha256、大小（`Cache-Control: no-store`） |
| `GET /kura-edge-<版本>.jar` | jar 本体（流式，带 `Content-Length`） |
| `GET /` | 人看的首页：目录里有哪些版本 + 设备该怎么配 |
| `GET /healthz` | 探活 |

只读，没有上传接口：jar 由构建脚本或人工放进 `releases/`。

## 设备侧怎么接

```bash
# 设备（exe 所在机器）上设系统环境变量（只配地址，清单路径自动推导，RFC 010）：
KURA_RELEASE_SERVER_URL=http://192.168.31.40:8081
```

之后设备页面的「升级」弹窗会显示更新源与最新版本，主按钮直接是「升级到 <版本>」；
也可以 `curl -u admin:<口令> -X POST http://<设备>:<端口>/api/v1/system/update -d '{"version":"1.0.3"}'`
—— 地址与 sha256 由本服务提供的清单给出，不用再写 jar 链接。

之后设备每 5 分钟拉一次清单，发现比本机新的版本就自己下载、校验 sha256 并走蓝绿热更新
（PostgreSQL 模式下页面零中断）。临时催一台：

```bash
curl -u admin:<口令> -X POST http://<设备>:<端口>/api/v1/system/feed/check   # 立即检查
curl -u admin:<口令> http://<设备>:<端口>/api/v1/system/feed                 # 看状态
```

## 两条现场约定

1. **文件名即版本**：只有 `kura-edge-<版本>.jar` 会被认（版本号 `[0-9A-Za-z][0-9A-Za-z._-]{0,31}`），
   其余文件忽略——设备侧对文件名有同一套白名单，不一致会表现为"清单里有、设备拒收"。
2. **只升不降**：清单里的版本比设备旧时设备不会动。要回退，用页面上的手动升级或直接重装 exe。

## 测试

```bash
mvn -o -pl kura-edge-release-server -am verify   # 单测：扫描/选最新/摘要/端点
```

`releases/*.jar` 不入库（见 `.gitignore`），只保留 `.gitkeep`。
