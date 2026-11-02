# kura-edge 前端操作手册（Wires 图编辑器）

本手册面向使用 kura-edge 前端编排 Wire 数据流的使用者，覆盖界面操作、组件属性、快照兼容性与排障。
前端与 `kura-edge-rest`（Spring Boot，默认 `8080`）配套使用：画布上的图、组件配置都通过 REST 读写后端。

---

## 1. 快速开始

### 1.1 前置条件

| 项目 | 要求 |
|---|---|
| Node.js | 24 LTS（本机在 `/home/idea/nodejs/bin`，非系统包管理器版本） |
| 后端 | `kura-edge-rest` 已在 `http://localhost:8080` 启动（数据库默认 H2 内存库） |
| 账号 | 后端 `wires.admin` 角色，默认 `admin` / `changeme`（见 `kura-edge-rest/src/main/resources/application.yml` 的 `kura.security.admin`，生产用环境变量 `KURA_EDGE_ADMIN_PASSWORD` 覆盖） |

### 1.2 启动前端

```bash
cd frontend
npm install

# 首次运行需要本地凭据（.env.* 已在根 .gitignore 中，不会入库）
cat > .env.local <<'EOF'
VITE_WIRES_USERNAME=admin
VITE_WIRES_PASSWORD=changeme
EOF

npm run dev          # → http://localhost:5173/
```

浏览器打开 `http://localhost:5173/`，路由会自动跳到 `/wires`（编辑器是当前唯一页面）。
开发服务器把 `/api` 代理到 `http://localhost:8080`（见 `vite.config.ts` 的 `server.proxy`），因此**前端不需要配置后端地址**。

> 缺 `.env.local` 时接口返回 `401`（`401001 未认证`），表现为「组件面板空 / 保存失败」，但页面本身可以打开。

### 1.3 常用命令

```bash
npm run dev            # 开发服务器（5173，热更新）
npm run build          # vue-tsc 类型检查 + 生产构建到 dist/
npm run preview        # 预览 dist 构建产物
npm run lint           # prettier --check src && eslint .
npm run test:unit      # vitest run
npm run test:coverage  # 覆盖率（阈值：语句 ≥70%，composables/ 与 stores/ ≥80%）
```

---

## 2. 界面总览

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 工具栏：保存 | 重新加载 | 导出快照 | 导入 JSON | 删除整图 | 删除组件 | － ＋ 适应画布 │
├───────────────┬──────────────────────────────────────┬───────────────────┤
│  组件面板      │               画布                    │     组件配置        │
│  （按 factory  │  节点 = 组件，右侧输出锚点 / 左侧输入锚点 │  选中节点的表单      │
│   PID 列出）   │  连线 = 数据流，可多端口                │                   │
└───────────────┴──────────────────────────────────────┴───────────────────┘
```

- **组件面板**（左）：后端 `GET /metadata` 返回的 7 个内置组件，中文名 + factoryPid。
- **画布**（中）：Vue Flow 画布，20px 网格吸附；空白处点击取消选中。
- **组件配置**（右）：选中组件后可编辑其 metatype 属性；字段标签为中文，标签旁的小字是上游 metatype 属性 id（便于与 Kura 文档、配置文件对照），鼠标悬停显示属性说明。

---

## 3. 操作详解

### 3.1 新建组件

两种方式：

1. **点击**面板项：在画布上按网格自动错开放置（每行 5 个）。
2. **拖拽**面板项到画布：落在鼠标位置。

新建时自动生成 PID：取 factoryPid 末段并去掉非法字符（`org.eclipse.kura.wire.Timer` → `Timer`），重名时递增（`Timer2`、`Timer3`…）。
端口数取组件定义的默认值，属性取各字段的 metatype 默认值。

> 画布状态只在浏览器内，**新建后要保存才落库**。

### 3.2 选中与配置组件

1. 点击画布节点 → 选中（蓝框），右侧出现该组件的配置表单。
2. 按字段类型渲染控件：

| metatype 类型 | 控件 |
|---|---|
| `BOOLEAN` | 下拉：`true` / `false` |
| 带 options 的字段 | 下拉：显示中文选项名，提交的仍是原 token（如 `SECONDS`） |
| `PASSWORD` | 密文输入（可点小眼睛查看） |
| 数值类型（`INTEGER/LONG/BYTE/FLOAT/DOUBLE`） | 文本框，占位符提示取值范围（如 `1 ~ 10`） |
| 其余 | 文本框 |

3. 字段校验（对照上游 `ValidationInputCell`，正反路径都校验）：

| 情况 | 提示 |
|---|---|
| 必填字段为空 | 该字段为必填项 |
| 数值字段非数字 / 整数类型填了小数 | 请输入合法数字 / 请输入合法整数 |
| 布尔字段非 `true`/`false` | 取值必须为 true 或 false |
| `CHAR` 字段不是单字符 | 只能输入单个字符 |
| 超出 `min`/`max` | 取值不能小于 {min} / 取值不能大于 {max} |
| 不在 options 内 | 取值必须在可选项中 |

4. 点 **应用配置**：校验全部通过才写入画布上的组件属性（提示「配置已应用」）；有字段失败则提示「存在非法字段，无法应用」，字段标红。

> 「应用配置」只改本地画布状态并置「未保存」，不会调用后端。

### 3.3 连线

- 从上游组件的**右侧输出锚点**拖到下游组件的**左侧输入锚点**。组件是多端口的：一个节点上每个端口各一个锚点，连线带端口编号（`emitterPort` / `receiverPort`），不是单纯连两个节点。
- 三条拒绝规则（与后端 `WireGraphEngine` 一致），违反时右上角报错：

| 情况 | 提示 |
|---|---|
| 端口编号超出组件端口范围 | 端口编号超出组件端口范围 |
| 完全相同的连线已存在 | 该连线已存在 |
| 会形成环（含自己连自己） | 不允许创建环路 |

- **删除连线**：直接点击那条线即删除（无二次确认）。

### 3.4 移动、缩放、平移

| 操作 | 方式 |
|---|---|
| 移动组件 | 拖拽节点，松手后吸附到 20px 网格 |
| 缩放 | 工具栏 `－` / `＋`；画布滚轮 |
| 平移 | 在空白处拖拽 |
| 一屏看全 | 工具栏「适应画布」（首次进入画布也会自动 fit） |
| 取消选中 | 点击画布空白处 |

### 3.5 删除组件

- 节点右上角的 `×`：直接删除。
- 或先选中，再点工具栏「删除组件」：弹二次确认（「确认删除选中的组件及其所有连线？」）。

删除组件会**联动删除它的所有连线**；只改本地状态，需保存才同步后端。

### 3.6 保存

工具栏「保存」→ `PUT /api/v1/wires/graph/snapshot`：

1. 前端先对整张图再做一次环检测（建线时已逐条校验，这里是兜底），有环直接拦下。
2. 装配载荷：第一条是图服务配置（`pid = org.eclipse.kura.wire.graph.WireGraphService`，`WireGraph` 属性 = 图 JSON 字符串），随后每个组件一条配置（属性带 `{type, value}` 包装，并补 `service.factoryPid`）。
3. 后端二次校验拓扑（环 → `409001`，端口不匹配 → `409002`）并落库，成功提示「保存成功」，清除未保存标记。

保存按钮在画布上没有任何组件时不可用。

### 3.7 重新加载 / 删除整图

| 操作 | 行为 |
|---|---|
| 重新加载 | `GET /graph/snapshot` 重新拉整图，画布重建。有未保存修改时先确认「未保存的修改将丢失，确认重新加载？」 |
| 删除整图 | `DELETE /graph`，二次确认「确认删除整个图？该操作会同步到后端。」（会清掉后端全部连线与组件配置） |

### 3.8 导出快照

工具栏「导出快照」→ 浏览器下载 `wires-snapshot-<时间戳>.json`，内容就是**图 JSON**（只有 `components` / `wires`，不含组件配置属性），可直接给上游 Kura 或本系统做对照。

### 3.9 导入 JSON

工具栏「导入 JSON」→ 选择文件，接受两种形态：

1. **图 JSON**：`{ "components": [...], "wires": [...] }`
2. **整份快照列表**：`{ "configs": [ ... ] }`（即 `GET /graph/snapshot` 的 `data`）

导入只在本地重建画布并标「未保存」，**需要再点保存才落库**（提示「导入成功（尚未保存）」）。
格式非法时提示「快照文件格式非法」。

> 图 JSON（形态 1）不含组件配置，导入时会**按 pid 向后端批量取配置**（`POST /configs/by-pids`）补回 `factoryPid` 与属性 —— 只要这些 pid 在后端有配置，画布上的组件就能直接编辑、应用配置并保存。
> 后端也查不到的 pid（例如来自别的环境的快照）补不回来，此时提示「导入成功（尚未保存），但组件 X 没有可用的组件配置、缺少 factoryPid」，保存会被拦住并列出这些组件。要完整往返，请使用形态 2 整份快照。

### 3.10 未保存离开提醒

有未保存修改时：

- 刷新/关闭页面：浏览器原生「离开此页？」提示。
- 切换前端路由：应用内确认框「当前有未保存的修改，确认离开？」。

---

### 3.11 现成的示例（`examples/`）

仓库自带两份可直接「导入 JSON」的示例（导入后仍需点保存才落库），**保存后会真的跑起来**：

| 文件 | 形态 | 说明 |
|---|---|---|
| `examples/demo-wires-snapshot.json` | `{ configs: [...] }` 整份快照 | 6 个组件 / 4 条连线的两条演示链路，**自带组件配置**，导入即可保存并运行 |
| `examples/demo-graph.json` | `{ components, wires }` 图 JSON | 同一张图的纯拓扑版本（只有端口与坐标）；pid 在后端有配置时才会自动补齐，见 3.9 |

```
TimerFast（每 1 秒）─▶ FifoQueue ─▶ CloudPublisherOut     定时源 → 缓冲 → 发布到日志水槽
TimerTick（每 5 秒）─▶ TimerKeyFilter ─▶ LoggerSink       定时源 → 按键过滤 → 日志
```

示例里的取值：上排 1 秒、下排 5 秒（SIMPLE/SECONDS）、正则只保留 `TIMER` 这个属性 key（RETAIN，该组件过滤的是
记录属性 key 而非取值）、FIFO 容量 100 且满时不丢新信封、云发布器发布后继续向下游转发、日志组件用 VERBOSE。

保存后后端日志里应出现（可用 `grep` 计数核对频率）：

```
Scheduled wire component TimerFast for periodic ticks: true
Publishing 1 wire record(s) to log sink            # 上排，每 1 秒
Received WireEnvelope from TimerKeyFilter          # 下排，每 5 秒
```

改「间隔时长」后保存，日志频率立刻变化（引擎会重建该组件实例并重新排程）；删除整图后日志停止。
各组件的确切语义、字段速查与改造步骤见 [`examples/README.md`](examples/README.md)。

## 4. 内置组件参考

端口数默认值来自组件定义；属性默认值来自 metatype（括号内为上游属性 id）。

### 4.1 定时器 Timer `org.eclipse.kura.wire.Timer`

输入 0 / 输出 1。周期性发出 Wire 信封：每次触发写入一条 `{ TIMER: 当前毫秒 }` 记录。
由引擎的调度器驱动（`SIMPLE` 固定速率、`CRON` 按表达式逐次排程），保存后立即开始、删图即停。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 间隔类型 `type` | SIMPLE / CRON | SIMPLE | 简单间隔或 CRON 表达式 |
| 间隔时长 `simple.interval` | INTEGER | 10 | 两次触发之间的间隔 |
| 时间单位 `simple.time.unit` | MILLISECONDS / SECONDS / MINUTES / HOURS | SECONDS | SIMPLE 下的时间单位 |
| 首次触发策略 `simple.first.tick.policy` | DEFAULT / CUSTOM | DEFAULT | 启动后首次触发行为 |
| 首次触发延迟 `simple.custom.first.tick.interval` | INTEGER | 0 | 仅 CUSTOM 策略生效 |
| CRON 表达式 `cron.interval` | STRING | `0/10 * * * * ?` | 选 CRON 时必填 |

### 4.2 日志记录器 Logger `org.eclipse.kura.wire.Logger`

输入 1 / 输出 0。把收到的信封打到日志（终端组件）。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 日志详细程度 `log.verbosity` | VERBOSE / QUIET | QUIET | 记录信封时的详细程度 |

### 4.3 正则过滤器 RegexFilter `org.eclipse.kura.wire.RegexFilter`

输入 1 / 输出 1。按正则过滤信封中的记录。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 正则表达式 `regex.filter` | STRING | 空 | 匹配用正则（可在 regexr.com 上调试） |
| 过滤类型 `filter.type` | 1 = 保留匹配项 / 2 = 移除匹配项 | 1 | RETAIN / REMOVE |

### 4.4 数据库记录存储 DbWireRecordStore `org.eclipse.kura.wire.DbWireRecordStore`

输入 1 / 输出 1。把信封中的记录写入后端数据库表。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 表最大记录数 `maximum.table.size` | INTEGER | 10000 | 表中保留的最大记录数 |
| 清理保留记录数 `cleanup.records.keep` | INTEGER | 5000 | 清理时保留的记录数（0 = 全删） |

### 4.5 FIFO 队列 Fifo `org.eclipse.kura.wire.Fifo`

输入 1 / 输出 1。异步缓冲，削峰。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 队列容量 `queue.capacity` | INTEGER | 50 | 队列可存信封上限 |
| 队列满时丢弃新信封 `discard.envelopes` | BOOLEAN | false | true 丢新信封；false 先发出最早的信封腾位 |

### 4.6 云发布者 CloudPublisher `org.eclipse.kura.wire.CloudPublisher`

输入 1 / 输出 1。把信封发布到云端。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 发布器目标过滤器 `CloudPublisher.target` | STRING | `(kura.service.pid=changeme)` | 指定发布器 PID；仅为兼容上游 metatype，本项目通过 Spring 注入绑定 |
| 发布后继续转发 `publish.forward` | BOOLEAN | false | true 时发布后仍把信封发往下游 |

### 4.7 云订阅者 CloudSubscriber `org.eclipse.kura.wire.CloudSubscriber`

输入 0 / 输出 1。把云端收到的消息注入图。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| 订阅源目标过滤器 `CloudSubscriber.target` | STRING | `(kura.service.pid=changeme)` | 指定订阅源 PID；仅为兼容上游 metatype，本项目通过 Spring 注入绑定 |

---

## 5. 快照格式与上游兼容性

导出的图 JSON（也是 `WireGraph` 字符串属性的内容）：

```json
{
  "components": [
    {
      "pid": "Timer",
      "inputPortCount": 0,
      "outputPortCount": 1,
      "renderingProperties": { "position": { "x": 80, "y": 80 } }
    }
  ],
  "wires": [
    { "emitter": "Timer", "emitterPort": 0, "receiver": "Logger", "receiverPort": 0 }
  ]
}
```

约定（与 Kura 原生快照一致，勿改名）：

- 顶层键只有 `components` / `wires`。
- 组件项：`pid` / `inputPortCount` / `outputPortCount` 同级；坐标与端口名在 `renderingProperties` 下。
- 组件配置属性（`service.factoryPid`、topic、正则等）**不在图 JSON 里**，而是在快照列表的独立条目中（`{pid, properties:{key:{type,value}}}`）。
- 端口数缺省时回落到组件定义的默认端口数（不补 0），因此可导入省略端口数的 Kura 原生快照。
- `PASSWORD` 类型字段在后端 GET 时脱敏为占位符 `PASSWORD`；保存时后端识别该占位符并**沿用库中真实口令**，不会把 `PASSWORD` 写进配置。

---

## 6. 后端接口对照（前端触发点）

| 前端操作 | 接口 |
|---|---|
| 打开页面加载面板 | `GET /api/v1/wires/metadata` |
| 打开页面 / 重新加载 | `GET /api/v1/wires/graph/snapshot` |
| 保存 | `PUT /api/v1/wires/graph/snapshot` |
| 删除整图 | `DELETE /api/v1/wires/graph` |
| （未在 UI 暴露）拓扑查询 | `GET /api/v1/wires/graph/topology` |
| 导入图 JSON 时补齐组件配置 | `POST /api/v1/wires/configs/by-pids` |
| （未在 UI 暴露）批量删除配置 | `DELETE /api/v1/wires/configs/by-pids` |
| （未在 UI 暴露）批量更新配置 | `PUT /api/v1/wires/configs` |
| （未在 UI 暴露）Driver / Asset 列表 | `GET /api/v1/wires/drivers`、`GET /api/v1/wires/assets`（当前项目恒为空） |

响应统一为 `{code, message, data}`，`code=0` 表示成功。认证：HTTP Basic + `wires.admin` 角色；`/swagger-ui.html`、`/v3/api-docs` 放行。

---

## 7. 排错

### 7.1 后端错误码

| code | HTTP | 含义 | 常见触发 |
|---|---|---|---|
| 400001 | 400 | 参数校验失败 | 载荷字段缺失/类型不符 |
| 400002 | 400 | 快照 JSON 非法 | `WireGraph` 属性缺失或不是合法 JSON |
| 400003 | 400 | 组件配置非法 | 组件缺 `factoryPid`、提交了不在图里的组件配置 |
| 409001 | 409 | 出现环 | 绕过前端直接 PUT 成环的图 |
| 409002 | 409 | 端口不匹配 | 端口编号超界 |
| 409003 | 409 | 资源被引用 | 删除仍被图引用的组件配置（UI 未暴露） |
| 404001 | 404 | 组件/PID 不存在 | — |
| 401001 / 403001 | 401 / 403 | 未认证 / 无 `wires.admin` | 缺 `.env.local` 凭据 |
| 500001 / 500002 | 500 | 图引擎 / 持久化错误 | 看后端日志 |

前端自身的拓扑与字段校验先于后端拦截，正常操作一般看不到 409001/409002。

### 7.2 常见问题

| 现象 | 原因与处理 |
|---|---|
| 组件面板显示「暂无组件定义」 | 接口 401：检查 `frontend/.env.local` 的用户名/口令是否与后端一致（后端改了 `KURA_EDGE_ADMIN_PASSWORD` 就要同步）；改完 `.env.local` 需重启 `npm run dev` |
| 导入提示「组件 X 没有可用的组件配置、缺少 factoryPid」 | 图 JSON 里的 pid 在后端没有配置，补不回 `factoryPid`；这些组件保存会被拦住（提示「以下组件缺少 factoryPid，无法保存：…」）。改用整份快照（`{configs:[...]}`）导入，或删掉该组件后从面板重建 |
| 所有 `/api` 请求 502 | 后端没起或不在 8080：`mvn spring-boot:run -pl kura-edge-rest` |
| 端口/环/重复连线报错 | 属预期校验，见 3.3 |

---

## 8. 已知限制

1. **图 JSON 里后端查不到的 pid 无法补齐**：这类组件缺 `factoryPid`，不能编辑属性、也不能保存（见 3.9）；整份快照（形态 2）不受影响。
2. **部分接口未在 UI 暴露**：`/graph/topology`、`/configs`、`DELETE /configs/by-pids`、`/drivers`、`/assets`（第 6 节）。
3. **无语言切换**：当前固定 `zh-CN`，`en-US` 只作为兜底；组件文案的中文表在 `src/i18n/zh-CN.ts` 的 `wires.metadata`。
