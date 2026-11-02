# 示例快照说明（`frontend/examples/`）

两份可直接「导入 JSON」的示例。**导入 + 保存后这张图会真的跑起来**：两个定时器按各自间隔触发，
数据沿链路流到日志组件与发布水槽。导入后画布只在浏览器内，点「保存」才落到后端。

| 文件 | 形态 | 用途 |
|---|---|---|
| `demo-wires-snapshot.json` | `{ "configs": [...] }` 整份快照（= `GET /api/v1/wires/graph/snapshot` 的 `data`） | 自带每个组件的配置，导入即可保存并运行，适用于任何后端 |
| `demo-graph.json` | `{ "components": [...], "wires": [...] }` 纯图 JSON | 只含拓扑与坐标，用来看线格式；导入时按 pid 向后端补齐配置（见主 README 3.9），后端没有这些 pid 的配置时补不齐、保存会被拦住 |

## 1. 这张图长什么样

```
y=80   TimerFast（每 1 秒）──▶ FifoQueue ──▶ CloudPublisherOut
y=240  TimerTick（每 5 秒）──▶ TimerKeyFilter ──▶ LoggerSink
```

| pid | 组件（factoryPid） | 输入/输出端口 | 坐标 |
|---|---|---|---|
| `TimerFast` | 定时器 `org.eclipse.kura.wire.Timer` | 0 / 1 | (80, 80) |
| `FifoQueue` | FIFO 队列 `org.eclipse.kura.wire.Fifo` | 1 / 1 | (360, 80) |
| `CloudPublisherOut` | 云发布者 `org.eclipse.kura.wire.CloudPublisher` | 1 / 1 | (640, 80) |
| `TimerTick` | 定时器 `org.eclipse.kura.wire.Timer` | 0 / 1 | (80, 240) |
| `TimerKeyFilter` | 正则过滤器 `org.eclipse.kura.wire.RegexFilter` | 1 / 1 | (360, 240) |
| `LoggerSink` | 日志记录器 `org.eclipse.kura.wire.Logger` | 1 / 0 | (640, 240) |

连线（多端口：端口号从 0 开始）：

```
TimerFast:0      → FifoQueue:0        FifoQueue:0      → CloudPublisherOut:0
TimerTick:0      → TimerKeyFilter:0   TimerKeyFilter:0 → LoggerSink:0
```

两条链路的角色不同：上排演示「定时源 → 缓冲 → 发布（日志水槽）」，下排演示「定时源 → 按键过滤 → 日志」。

## 2. 导入步骤与预期现象

1. 后端 `8080` 与前端 `5173` 都起来（见主 README 第 1 节）。
2. 工具栏「导入 JSON」→ 选 `demo-wires-snapshot.json`。
3. 看到「导入成功（尚未保存）」→ 点工具栏「保存」。
4. **保存后立刻开始出数据**，后端日志里能看到：

```text
Wire graph updated: 6 component(s), 4 wire(s)
Scheduled wire component TimerFast for periodic ticks: true
Scheduled wire component TimerTick for periodic ticks: true
# 每 1 秒（上排）：
Publishing 1 wire record(s) to log sink
  TIMER : 1792254334554
# 每 5 秒（下排）：
Received WireEnvelope from TimerKeyFilter
    TIMER : 1792254336555
```

自行核对频率：

```bash
grep -c "Publishing .* wire record" <后端日志>             # 上排：每秒 +1
grep -c "Received WireEnvelope from TimerKeyFilter" <日志> # 下排：每 5 秒 +1
```

5. 想看更快：改下排定时器的「间隔时长」为 2 → 应用配置 → 保存，日志立刻变成每 2 秒一条
   （引擎会重建该组件实例并重新排程，见第 4 节）。
6. 想清空：工具栏「删除整图」（或 `DELETE /api/v1/wires/graph`）—— 日志随即停止，无残留调度。

## 3. 每个组件配了什么、为什么

| pid | 属性 | 取值 | 说明 |
|---|---|---|---|
| `TimerFast` | `type` | `SIMPLE` | 固定速率模式（另一档 `CRON` 用 `cron.interval`） |
| | `simple.interval` | `1` | 间隔值；metatype 的 `min` 是 1 |
| | `simple.time.unit` | `SECONDS` | 可选毫秒/秒/分/时 |
| | `simple.first.tick.policy` | `DEFAULT` | 首次触发延迟 = 一个周期 |
| | `simple.custom.first.tick.interval` | `0` | 仅 `CUSTOM` 策略生效 |
| | `cron.interval` | `0/10 * * * * ?` | 仅 `CRON` 类型生效，这里保留默认值 |
| `FifoQueue` | `queue.capacity` | `100` | 队列上限 |
| | `discard.envelopes` | `false` | 满了先发出最旧的信封腾位（`true` 则丢新到的） |
| `CloudPublisherOut` | `CloudPublisher.target` | `(kura.service.pid=changeme)` | 保留上游 metatype 字段名；本项目改用 Spring 注入绑定，这个值只是兼容占位 |
| | `publish.forward` | `true` | 发布后继续把信封发给下游（本图下游为空，实际效果只是发布） |
| `TimerTick` | `type` / `simple.interval` / `simple.time.unit` | `SIMPLE` / `5` / `SECONDS` | 下排的慢速源 |
| `TimerKeyFilter` | `regex.filter` | `TIMER` | **过滤的是记录属性 key**，不是取值 |
| | `filter.type` | `1` | 1 = RETAIN（保留匹配 key），2 = REMOVE（移除匹配 key） |
| `LoggerSink` | `log.verbosity` | `VERBOSE` | `VERBOSE` 会逐条打记录属性，`QUIET` 只打信封来源 |

云发布者的发布水槽在本项目里默认是日志实现（`LogPublisherSink`），所以上排的「发布」体现为
`Publishing N wire record(s) to log sink`；接真实云平台时替换 `PublisherSink` 实现即可。

## 4. 运行期语义（保存之后到底发生了什么）

- **调度**：引擎在组件实例创建时发现它实现了 `ScheduledEmitter` 就按配置排程 ——
  `SIMPLE` 走固定速率（首次延迟 = 周期，除非 `CUSTOM` 策略），`CRON` 每次触发后按表达式算下一次。
  组件自身不建线程，全部由引擎的单线程调度器 `wire-graph-scheduler` 驱动。
- **缓冲**：`Fifo` 接收时只入队，引擎在投递线程池上异步调用 `drain()` 把队列排空；
  满了且 `discard.envelopes=false` 时先发出队头。
- **过滤**：`RegexFilter` 对**每条记录的属性 key** 做正则匹配，`RETAIN` 只保留匹配的 key
  （所以示例用 `TIMER` 才能保住定时器写入的时间戳）。
- **日志**：`Logger` 一律打 `Received WireEnvelope from <上游 pid>`，`VERBOSE` 再逐条打 `key : value`。
- **热更新**：改组件配置后保存，引擎发现运行期属性变化就**销毁并重建该实例**（重新排程）；
  只改坐标不会重建（拖动节点不会重启定时器）。
- **停止**：删图 / 关应用会取消所有排程，不会有残留任务继续触发。
- **云订阅者**：本示例不含云订阅组件 —— 它只有被外部调用 `deliver(envelope)` 时才会发射，
  本仓库尚未接入真实云连接。想演示「云端入站」时，把它加入图并接一个 `Logger`，
  再从云连接适配层调用它的 `deliver(...)`。

## 5. 字段速查

**纯图 JSON**（`demo-graph.json`）—— 字段名是与上游 Kura 的兼容契约，不要改名：

| 字段 | 含义 |
|---|---|
| `components[].pid` | 组件实例 PID（唯一） |
| `components[].inputPortCount` / `outputPortCount` | 端口数；缺省时按组件定义的默认端口数回落 |
| `components[].renderingProperties.position.{x,y}` | 画布坐标（画布按 20px 网格吸附） |
| `wires[].emitter` / `emitterPort` | 上游组件 pid 与其输出端口号 |
| `wires[].receiver` / `receiverPort` | 下游组件 pid 与其输入端口号 |

**整份快照**（`demo-wires-snapshot.json`）：

| 字段 | 含义 |
|---|---|
| `configs[0].pid` | 固定为 `org.eclipse.kura.wire.graph.WireGraphService` |
| `configs[0].properties.WireGraph` | `{type:"STRING", value:"<图 JSON 字符串>"}`（就是上面那份纯图 JSON，需转义成字符串） |
| `configs[1..].pid` | 各组件实例 PID，必须与图 JSON 里的 `pid` 一一对应 |
| `configs[1..].properties.service.factoryPid` | `{type:"STRING", value:"<组件 factoryPid>"}`，**必填**，缺失则保存被拦（提示「以下组件缺少 factoryPid，无法保存」） |
| 其它属性 | `{type, value}` 包装，`type` 取 metatype 标量（`STRING` / `INTEGER` / `BOOLEAN` / `PASSWORD` …） |
| `PASSWORD` 类型 | 后端 GET 时脱敏为占位符 `PASSWORD`，保存时识别占位符并沿用库中真实口令 |

## 6. 改成你自己的链路

1. **换组件**：改 `pid`（唯一即可，习惯用组件名 + 用途），并把 `service.factoryPid` 换成想用的工厂 PID
   （可用值见 `GET /api/v1/wires/metadata` 的 `wireComponentDefinitions[].factoryPid`）。
2. **加组件必须两处都改**：图 JSON 的 `components` 里加一条，快照的 `configs` 里再加一条配置 ——
   少了配置条目导入后就没有 `factoryPid`，保存会被拦住。
3. **端口要对得上**：`emitterPort` 必须 < 上游组件的 `outputPortCount`，`receiverPort` 必须 < 下游的 `inputPortCount`；
   连反、连出界、重复连线、成环都会被前端拦下（后端保存时再校验一次）。
4. **坐标**：按 20 的倍数写，导入后不会跳位；同列用 360/640 之类的固定列宽便于阅读。
5. **想试 CRON**：把 `type` 改成 `CRON`、`cron.interval` 写成 6 段 Spring 表达式（如 `0/10 * * * * ?`），
   `simple.*` 会被忽略；引擎按表达式逐次排程。
6. 改完把 `WireGraph` 属性重新序列化成字符串（手工改容易把转义弄错，可用一段脚本生成）。

## 7. 怎么确认示例没被改坏

- `npm run test:unit`：`src/api/__tests__/demoExamples.spec.ts` 会解析两份示例，校验组件/连线数量与每个组件都有
  `service.factoryPid`。改示例后跑一遍即可。
- 手测清单：导入 → 保存 → 后端日志出现上面第 2 节的两组行 → 改间隔后频率变化 → 删图后日志停止。
