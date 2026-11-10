# Wire Graph 运行期流程：CRON 定时器 → 驱动采集 → 日志记录

> **数据基准**：`/home/idea/.kura-edge/data/kuraedge.mv.db` 的当前内容。同目录的 `kuraedge.lock.db`
> 只是 H2 的文件锁（内容是 `server=…`、`hostName=…`、`method=file` 这类锁元数据），业务数据不在里面；
> 导出方法见附录 A，三张表的实际内容见第 1 节。
>
> **本文描述的那张图**：`Timer`（CRON，每 2 秒）→ `s1`（WireAsset，绑 simulated 驱动 `s`）→ `Logger`（QUIET）。
> 不是 `frontend/examples/demo-wires-snapshot.json` 里的 demo 图。
>
> **代码位置**：行号以当前工作区代码为准。`WireGraphEngine.java` 变动最频繁（本轮已整体后移约 43 行），
> 改代码后请按第 8 节逐条核对；行号只作导航，权威仍是 grep 结果。

---

## 0. 八步速览

一次「定时器 → 采集 → 日志」由 **8 个步骤**组成。只有第 3 步在调度线程上，第 4 步是线程切换点：

| # | 步骤 | 触发时机 | 线程 | 主要代码 |
|---|---|---|---|---|
| 0 | 启动期装零件（工厂/驱动/资产/路由） | 启动 | main / Tomcat | `DriverServiceImpl#init:74`（reloadAll:197）、`WireGraphEngine#loadGraph:492` |
| 1 | 组件实例创建后挂排程 | 保存整图时 | http-nio | `WireGraphEngine#scheduleInstance:583` |
| 2 | 算下一拍、排 JDK 任务 | 步骤 1 内 + 每拍结束 | wire-graph-scheduler | `scheduleNextCronTick:601` → `scheduleCronTickAfter:613` |
| 3 | 到点唤醒 → `Timer#tick()` 构造并发射信封 | 每 2 秒 | wire-graph-scheduler | `runCronTick:648`、`awaitFireTime:678`、`Timer.java:67` |
| 4 | 路由 + 投递到 `s1` 的 lane | 每拍 | scheduler → delivery | `WireGraphEngine#dispatch:533`、`EnvelopeRouter#dispatch:72`、`DeliveryDispatcher#submit:80` |
| 5 | `WireAsset` 收到信封、决定读哪些通道 | 每拍 | wire-envelope-delivery-* | `WireAsset#onWireReceive:83`、`BaseAsset#readAllChannels:90` |
| 6 | 解析驱动并 `read`（两次查找 + 直接调用） | 每拍 | delivery（同 JVM） | `DriverServiceImpl#findDriver:116`、`SimulatedDriver#read:107` |
| 7 | 组装采集结果成信封、再投递 | 每拍 | delivery | `RecordFillers#toEnvelopeProperties:37`、`WireAsset#emit:132` |
| 8 | Logger 记录 + SSE 推页面 | 每拍 | delivery → wire-log-sse | `Logger#onWireReceive:74`、`ComponentLogBuffer#append:62`、`ComponentLogStreamer#pushPending:125` |

数据流向（一张图看懂）：

```
Timer#tick ──信封 {"TIMER": now}──▶ WireAsset#onWireReceive ──read──▶ SimulatedDriver#read
                                                                          │  register-1 未写 ⇒ 零值
                                                                          ▼
Logger「Received WireEnvelope from s1」 ◀──信封 {"Channel-1":0, "Channel-1_timestamp":…} ◀──┘
```

---

## 1. 数据基准：库里的图

### 1.1 `COMPONENT_CONFIGURATION`：4 行

| PID | FACTORY_PID | 本流程用到的关键属性 |
|---|---|---|
| `Timer` | `org.eclipse.kura.wire.Timer` | `type=CRON`、`cron.interval=0/2 * * * * ?`、`simple.interval=2`、`simple.time.unit=SECONDS`、`simple.first.tick.policy=DEFAULT` |
| `s1` | `org.eclipse.kura.wire.WireAsset` | `driver.pid=s`、`Channel-1#+enabled=true`、`Channel-1#+type=READ`、`Channel-1#+value.type=INTEGER`、`Channel-1#simulated.register=register-1`、`Channel-1#+listen=false`、`timestamp.mode=PER_CHANNEL`、`emit.empty.envelopes=true`、`emit.errors=false`、`emit.connection.errors=false`、`emit.on.change=false` |
| `Logger` | `org.eclipse.kura.wire.Logger` | `log.verbosity=QUIET` |
| `s` | `org.eclipse.kura.driver.simulated` | 驱动**实例**（PID `s`）；`properties.service.factoryPid=org.eclipse.kura.driver.simulated` 指向 `SimulatedDriverFactory` |

> `Timer` 的 `simple.*` 在本图**不生效**：`TimerOptions#isSimple:71` 只认 `type=SIMPLE`，
> 本图是 `CRON` ⇒ `Timer#isPeriodic:80` 返回 `false` ⇒ 走「逐次排程」而非 `scheduleAtFixedRate`。

### 1.2 `WIRE_GRAPH_SNAPSHOT`：1 行，拓扑的唯一权威

`SERVICE_PID = org.eclipse.kura.wire.graph.WireGraphService`，`GRAPH_JSON`：

```json
{"components":[
  {"pid":"Timer","inputPortCount":0,"outputPortCount":1,
   "renderingProperties":{"position":{"x":-100.0,"y":80.0},"inputPortNames":{},"outputPortNames":{}}},
  {"pid":"s1","inputPortCount":1,"outputPortCount":1,
   "renderingProperties":{"position":{"x":80.0,"y":80.0},"inputPortNames":{},"outputPortNames":{}}},
  {"pid":"Logger","inputPortCount":1,"outputPortCount":0,
   "renderingProperties":{"position":{"x":280.0,"y":80.0},"inputPortNames":{},"outputPortNames":{}}}],
 "wires":[
  {"emitter":"Timer","emitterPort":0,"receiver":"s1","receiverPort":0},
  {"emitter":"s1","emitterPort":0,"receiver":"Logger","receiverPort":0}]}
```

```
Timer（0 入 / 1 出，CRON 每 2 秒）──port 0──▶ s1（1 入 / 1 出，WireAsset）──port 0──▶ Logger（1 入 / 0 出）
```

- 快照只承载**拓扑 + 渲染属性**（`pid`/端口数/坐标），不含业务属性；业务属性在 `COMPONENT_CONFIGURATION`，
  由 `WireGraphEngine#loadGraph:492` 里的 `withComponentConfigurations:512` 回填。
- `s1` 既是资产又是图组件，PID 与 Drivers & Assets 页面的资产 PID 是同一个。

### 1.3 `WIRE_RECORDS`：0 行（符合预期）

| 项 | 值 |
|---|---|
| 唯一写入口 | `DbWireRecordStore#onWireReceive:89`（`cleanupIfNeeded:112`，属性 `maximum.table.size:42` / `cleanup.records.keep:45`） |
| 本图为什么是 0 行 | 末端是 `Logger`，**没有 DbStore 节点** |
| 「日志记录」走的另一条路 | SLF4J + `ComponentLogBuffer`（第 8 步），不是这张表 |

要落库，需要把 Data Store 组件加到 `s1 → Logger` 之间（或作为旁路节点）。

---

## 2. 步骤 0：启动期把零件装起来

> 下面的方块表达「谁在什么阶段做什么」，Spring Bean 之间的创建顺序没有硬保证；
> 真正的硬约束只有一条：图引擎构造器只恢复快照与路由，组件实例不在这里创建。

```
Spring 创建 Bean
├─ WireComponentRegistry：注入全部 WireComponentFactory（Timer/Logger/WireAsset…）
│   └─ setEnvelopeDispatcher:82 / addListener:112
├─ DriverServiceImpl#init @PostConstruct (:73) → reloadAll (:197)
│    └─ 工厂是 Spring 注入的 @Component ⇒ 按 COMPONENT_CONFIGURATION 建驱动实例 s（SimulatedDriver）
├─ AssetServiceImpl#init @PostConstruct (:69) → reloadAll (:167)
│    └─ 按 COMPONENT_CONFIGURATION 重建资产 s1（WireAsset 实例）
│         └─ registry.createInstance:138 → notifyCreated:254 → 监听器 onInstanceCreated:219
│              └─ s1 不是 ScheduledEmitter ⇒ 不排程；只 refreshRoutes:529
├─ WireGraphEngine 构造器
│    ├─ :215 registry.setEnvelopeDispatcher(this::dispatch)
│    ├─ :219 / :225 注册 onInstanceCreated / onInstanceDestroyed 监听器
│    └─ :492 loadGraph() → 读快照 + 回填组件配置 + refreshRoutes:529
│         └─ **只建路由表，不创建 Timer / Logger 实例**
│         └─ 日志 "Wire graph engine started" (:235)
```

三条结论：

1. **驱动与资产在启动时恢复**（各自 `@PostConstruct` 里 `reloadAll`）。
2. **图组件实例（`Timer`、`Logger`）不是启动时创建的**，而是保存整图时由 `WireGraphEngine#update:293`
   的 `apply:381` 创建：`PUT /api/v1/wires/graph/snapshot`（`WireGraphController:76`）
   → `WireGraphSnapshotService#updateSnapshot:106` → `:196 wireGraphService.update(...)`
   → `registry.createInstance:138` → 监听器 → `scheduleInstance:583`。
3. **已知边界（重启语义）**：库是文件模式 H2（`kura-edge-rest/src/main/resources/application.yml:12`，
   `AUTO_SERVER=TRUE`），所以快照与组件配置重启后仍在、图不会「变空」；但**图里的组件实例不会自动重建**——
   引擎启动只恢复快照与路由表，`Timer`/`Logger` 的实例只由保存时的 `update` 创建。
   按当前代码，重启后再保存一次**属性完全相同**的图也不会重建实例——`apply` 的 retained 分支只在
   `propertiesChanged:728` 为真时才 `rebuildInstance:774`。要让定时器重新跑起来，需要改一次该组件的运行期属性
   （触发重建）或把它从图上删除后重新加入。这是行为边界，不是本流程的预期工作点。

---

## 3. 主流程：一拍走完 8 步

### 步骤 1 · 挂排程（组件实例创建的那一刻）

**做什么**：组件实例一创建，注册表同步回调引擎；只有 `ScheduledEmitter`（本图是 `Timer`）才挂排程。

```java
// WireGraphEngine#scheduleInstance (:583)
if (!(instance instanceof ScheduledEmitter emitter)) return;      // Logger / WireAsset 直接返回
if (emitter.isPeriodic()) {                                       // Timer: type=CRON ⇒ false
    tickScheduler.schedulePeriodic(pid, () -> tickIfCurrent(pid, emitter, generation),
            emitter.getInitialDelayMillis(), emitter.getPeriodMillis());   // :589
} else {
    scheduleNextCronTick(pid, emitter, generation);                // :592 ← 本图走这条
}
log.info("Scheduled wire component {} for periodic ticks: {}", pid, emitter.isPeriodic());   // :594
```

| 项 | 说明 |
|---|---|
| 触发者 | `WireComponentRegistry#notifyCreated:254`（`createInstance:138` 末尾同步通知） |
| 监听器 | `WireGraphEngine` 构造器 `:219`（创建）/ `:225`（销毁） |
| 失败行为 | 只记 `ERROR "Failed to schedule wire component {}: {}"`（`:595`），**不阻断整图更新** |
| 世代号 | 每次创建/销毁 `nextGeneration:720`，任务带上创建时的代数（见步骤 3） |

### 步骤 2 · 算下一拍并排 JDK 任务（CRON 逐次排程）

```java
// scheduleNextCronTick (:601) → scheduleCronTickAfter (:613)
if (!isCurrentGeneration(pid, generation)) return;        // :615 上一代实例的链到此为止
final Instant next = emitter.nextCronFireTime(after);     // Timer.java:143
tickScheduler.scheduleOnce(pid, () -> runCronTick(pid, emitter, generation, next),
        delayMillisCeil(now, next));                      // :627 / :628，向上取整毫秒，绝不早于计划时刻
```

- `nextCronFireTime`（`Timer.java:143`）= `CronExpression.parse("0/2 * * * * ?")`
  `.next(after 转 UTC ZonedDateTime).toInstant()`。
- `scheduleOnce`（`ScheduledExecutorTickScheduler:63`）落到**单守护线程** `wire-graph-scheduler`
  （`newSingleThreadScheduler:104`）的 `ScheduledExecutorService.schedule(...)`。
- `delayMillisCeil:703` 向上取整是必须的：`Duration.toMillis()` 向下截断会让任务提前 <1 ms 唤醒，
  时间戳变成 `:49.999Z`。

### 步骤 3 · 到点唤醒并发射信封

```java
// runCronTick (:648)
if (!awaitFireTime(pid, emitter, generation, fireAt)) return;   // :650
tickIfCurrent(pid, emitter, generation);                        // :654 → :708
finally { scheduleCronTickAfter(pid, emitter, generation, after.isAfter(fireAt) ? after : fireAt); }  // :659
```

- `awaitFireTime:678`：剩余 ≤ `MAX_IN_PLACE_WAIT_NANOS`（2 ms，`:83`）就 `LockSupport.parkNanos` 原地等过去；
  剩余更多说明唤醒过早 ⇒ 重新 `scheduleOnce`（`:685`），本次不发射，也不占住共享调度线程。
- `tickIfCurrent:708` → `isCurrentGeneration:724` 通过才 `emitter.tick()`。
- **续排起点是「本次计划触发时刻 `fireAt`」而不是 `Instant.now()`**：用 `now` 会在提前唤醒时算回同一拍，
  导致同一拍发两次。
- `Timer#tick:67` 构造信封并**只投递**：

```java
final WireRecord record = new WireRecord(Map.of("TIMER", TypedValues.newLongValue(System.currentTimeMillis())));
this.emitContext.emit(new WireEnvelope(this.pid, List.of(record)));   // 立即返回，不等下游
```

本图参数：

| 项 | 值 | 说明 |
|---|---|---|
| `type` | `CRON` | 只读 `cron.interval`，`simple.*` 不参与 |
| `cron.interval` | `0/2 * * * * ?` | 每 2 秒（第 0、2、4… 秒）一拍 |
| 触发频率 | 每 2 秒 1 次（0.5 Hz） | **与下游耗时无关**，Timer 不等 read |
| 首次触发 | 保存后 2 秒内 | 由 `nextCronFireTime(now)` 决定，落在整秒 |
| 保存时日志 | `Scheduled wire component Timer for periodic ticks: false` | `false` 即「非 SIMPLE」 |

### 步骤 4 · 路由与投递（唯一的线程切换点）

```
wire-graph-scheduler
  Timer#tick → emitContext.emit
    └─ WireGraphEngine#dispatch("Timer", envelope)                    :533
        └─ EnvelopeRouter#dispatch("Timer", envelope)                 :72
            ├─ routesByEmitter["Timer"] = [wire(Timer→s1, port 0)]    （表由 refresh:58 建）
            ├─ registry.getInstance("s1") → WireAsset 实例
            └─ DeliveryDispatcher#submit("s1", () -> deliver(...))    :80
                 └─ 入 s1 的 lane 队列后**立刻返回**（这里不读驱动）
```

lane 线程（`wire-envelope-delivery-*`）里才真正执行：

```java
// EnvelopeRouter#deliver (:80)
receiver.onWireReceive(envelope);                    // → WireAsset#onWireReceive:83
if (receiver instanceof DrainableComponent d) d.drain();   // Fifo 专属，本图没有
catch (RuntimeException e) → WARN "Component {} failed to handle wire envelope"  (:89)
```

投递并发模型（RFC 004，摘要）：

| 项 | 值 / 行为 | 代码 |
|---|---|---|
| 投递池 | 有界，默认 `max(4, CPU×2)` 线程 + 共享队列 512，线程名 `wire-envelope-delivery` | `newDeliveryExecutor:793` |
| 分区 | 每个接收 PID 一条 **lane**，同 lane 串行有序，默认排队上限 256 | `Lane:128`、`Lane#submit:140` |
| 溢出 | lane 满丢**新**信封并计入 `droppedByReceiverQueue`（WARN 每 100 条限流一次） | `:143`、`:216` |
| 池拒绝 | 该组件的排空任务推迟，计入 `rejectedDrains`（信封不丢） | `Lane#submit` |
| 观测 | `WireGraphEngine#getDeliveryStats:243` 暴露投递量/丢弃量 | `stats:90` |

系统属性：`wires.engine.delivery.threads` / `.queue-capacity` / `.receiver-queue-capacity`
（`DeliverySettings:26/29/32`）。

### 步骤 5 · `WireAsset` 收到信封：先读、再决定是否写

```java
// WireAsset#onWireReceive (:83)
emitAllReadChannels();                                   // :87 —— 每个信封都先读一遍
for (final WireRecord record : envelope.getRecords()) {
    writeChannels(determineWritingChannels(record));     // :89 —— 写设备分支，本图空转
}
```

`emitAllReadChannels:93` → `readAllChannels:90`（读哪些通道由 `getAllReadRecords:187` 决定：
`+enabled=true` 且 `type != WRITE`，本图 1 条 `Channel-1`）。

要点：

- **一条信封 = 一次 read**，没有节流也没有缓存；`emit.on.change` 只过滤**发射**，读照样发生。
- 读失败只记 `ERROR "Error while performing read from the Wire Asset: {}"`（`:100`）；
  本图 `emit.errors=false && emit.connection.errors=false` ⇒ **不发错误信封**，表现为「定时器在跑、Logger 没输出」。
- 写入分支：`determineWritingChannels:140` 只认 `WRITE`/`READ_WRITE`，本图 `Channel-1` 是 `READ` ⇒ 空转；
  即使改成 `READ_WRITE`，收到的记录里也只有 `TIMER` 属性，仍然匹配不到。

### 步骤 6 · 解析驱动并 `read`（两次查找 + 直接调用）

#### 6.1 第一次查找：资产 → 驱动实例

```java
// BaseAsset#requireDriver (:225)
final String driverPid = this.configuration.getDriverPid();          // "s"
final Driver resolved = this.driverService.findDriver(driverPid)     // DriverServiceImpl:116（只按 PID 查 map）
        .orElseThrow(() -> new WiresException(CHANNEL_IO_FAILED, DRIVER_NOT_ATTACHED));   // :228 / :231
if (resolved != this.driver) { this.driver = resolved; this.connected = false; }
if (!this.connected) { resolved.connect(); this.connected = true; }   // 懒连接
```

#### 6.2 第二次查找：实例 → 工厂 → 真正 `new` 出来的驱动

图里 `s1` 只是一个 PID；用哪个驱动**不在快照里**，而在资产配置的 `driver.pid` 上：

```
s1（资产/组件 PID）
 └─ COMPONENT_CONFIGURATION[s1].driver.pid = "s"                     ← 驱动「实例 PID」，不是 factoryPid
     └─ DriverServiceImpl#findDriver("s")                            :116（instances: 实例PID → Driver）
         └─ COMPONENT_CONFIGURATION[s].service.factoryPid = "org.eclipse.kura.driver.simulated"
             └─ factories["org.eclipse.kura.driver.simulated"]
                 └─ SimulatedDriverFactory#create:53 → new SimulatedDriver("s", …)
```

| 层 | 查找用的键 | 本库的值 | 代码 |
|---|---|---|---|
| ① 资产 → 驱动实例 | `driver.pid` | `s` | `AssetConstants.ASSET_DRIVER_PROP:32`；`AssetChannelParser#parse:46` |
| ② 按实例 PID 取驱动 | 实例 PID | `s` | `BaseAsset#requireDriver:225` → `DriverServiceImpl#findDriver:116`（**不认 factoryPid**） |
| ③ 实例 → 工厂 | `[s].service.factoryPid` | `org.eclipse.kura.driver.simulated` | `DriverServiceImpl#reloadAll:197` → `factoryPidOf:215` → `createInstance:220` |
| ④ 工厂注册（启动时） | factoryPid → `DriverFactory` | `SimulatedDriverFactory`（`@Component`，Spring 注入） | `DriverServiceImpl#init:74` → `reloadAll:197` |
| ⑤ 实例创建 | `factory.create("s", props)` | props 来自第 ③ 步 | `DriverServiceImpl#createInstance:220` |
| ⑥ 工厂匹配 | **`service.factoryPid`** | 同上 | `DriverServiceImpl#isDriverFactory` / `factoryPidOf:215` |
| ⑦ 真正 new 出来 | 同上 | `new SimulatedDriver("s", props)` | `SimulatedDriverFactory#create:53` |

四个容易踩的点：

1. **`driver.pid` 是驱动实例 PID。** 新建资产时 `DriversAssetsService#createAsset:165` 会校验它存在；
   但**资产更新与图保存都不校验**（`updateAsset:196`、`WireGraphEngine#update:293` 只看组件 factoryPid），
   所以错误值能保存成功，第一次读才炸：`WiresException(CHANNEL_IO_FAILED, "Driver not attached")`
   （`BaseAsset.java:228`、`:231`，常量 `:47`）。
2. **两侧都靠 `service.factoryPid` 对齐**：`DriverServiceImpl` 用它判定「这行是驱动配置」
   （`isDriverConfig:211`）并决定用哪个工厂（`factoryPidOf:215`）。这就是导入快照必须带组件配置、
   不能只导图 JSON 的原因。
3. **工厂是 Spring 扫描的**：`SimulatedDriverFactory` / `ModbusDriverFactory` 都是 `@Component`，
   由 `DriverServiceImpl` 构造器注入；没有「先注册工厂 → 再建实例」的外部顺序依赖（第 2 节）。
4. **通道参数 `simulated.register` 来自工厂描述符**：`SimulatedChannelDescriptor:25`（`REGISTER_PARAM`）
   / `:28`（`DEFAULT_REGISTER=register-1`）→ `SimulatedDriverFactory#getChannelDescriptor:48` →
   `DriverServiceImpl#getChannelDescriptor:98` → 保存资产时 `AssetServiceImpl#getChannelAdsForDriver:201`
   取来，由 `AssetChannelParser#withChannelDefaults:80` 回填 `Channel-1#simulated.register=register-1`。

> **驱动在哪跑？** 与后端同一个 JVM：`SimulatedDriverFactory` / `ModbusDriverFactory` 是 `@Component`，
> 实例由 `DriverServiceImpl#createInstance:220` 创建，`read` 由调用方线程直接执行。

#### 6.3 一次 read 的调用链

```
BaseAsset#requireDriver:225（懒连接后）
 └─ DriverServiceImpl#findDriver("s"):116      同 JVM 直接拿实例
     └─ SimulatedDriver#read:107
         ├─ registerOf(record):145 → "register-1"（通道参数 simulated.register，缺省即默认寄存器）
         ├─ registers 里没有该寄存器 ⇒ zeroValueOf(INTEGER):203 = IntegerValue(0)
         ├─ setChannelStatus(SUCCESS)
         └─ setTimestamp(System.currentTimeMillis())
 └─ ScaleOffsetApplier.apply（scale=1.0 / offset=0.0 ⇒ 值不变）
```

#### 6.4 采集节奏：谁触发、多久一次、能不能手动

**驱动是纯被动的：`SimulatedDriver` 没有自己的采集线程/定时器。** `DriverServiceImpl#createInstance:220` 只是
把实例放进 `instances` map，构造器（`SimulatedDriver.java:57`）只保存 pid 与 properties；`driver/` 与
`asset/` 下既没有 `ScheduledExecutor`，也没有 `new Thread` / `@Scheduled`。所以「驱动采集周期」
这个概念在本项目里不存在——节奏完全由图里的 Timer 决定。

| 问题 | 答案 | 依据 |
|---|---|---|
| 谁触发 `read`？ | Timer 的每一拍，间接经 `WireAsset#onWireReceive` | `WireAsset.java:83`、`:93` |
| 多久一次？ | 等于 `cron.interval`，本图 ⇒ 每 2 秒一次 | `Timer.java:143`、库配置 |
| 跑在哪条线程？ | `wire-envelope-delivery-*`（s1 的 lane），**不在** `wire-graph-scheduler` | `DeliveryDispatcher.java:140`、`:184` |
| 一次读几条通道？ | 一次批量调用，含全部可读通道（本图 1 条 `Channel-1`） | `BaseAsset.java:90`、`:187` |
| 能不等 Timer 直接读吗？ | 能，REST `GET\|POST /api/v1/assets/{pid}/_read` | `AssetDataController.java:73`、`:87` |

配套边界：

- **`create` 与 `read` 零耦合**：建实例（`createInstance:220`）时不会读一次。
  唯一把两者连起来的是「信封」——**把 `Timer` 从图上删掉，`s1` 就永远不读**（资产还在，但没人敲门）。
- **读慢的后果落在 lane 上**：同一条 lane 同时只跑一个任务（`Lane#submit:140` / `drainLoop:184`），
  排队上限 256；满了丢新信封并计数（`getDeliveryStats:243`）。定时器自己照常每 2 秒发，不等待。
- **连接懒建立且只建一次**：`requireDriver:225` 在 `connected=false` 时才 `connect()`
  （`DriverServiceImpl#findDriver:116` → `SimulatedDriver#connect:81`）；
  `SimulatedDriver#connect:81` 只置标志，没有握手、没有超时。
- **不能改成「驱动主动推」**：本图 `Channel-1#+listen=false`，且即使打开也无实现——
  驱动实现（`SimulatedDriver#registerChannelListener:130`、`ModbusDriver.java:149`）只记一条 debug 日志，不回调。
- **图内采集只有这一条路径**：手动 REST 读绕开图与定时器，直接调资产实例的 `readAllChannels`，
  不产生信封，也不进 Logger。

### 步骤 7 · 组装采集结果成信封

`emitChannelRecords:114` → `RecordFillers#toEnvelopeProperties:37`：

| 信封属性 | 类型 | 值 | 来源 |
|---|---|---|---|
| `Channel-1` | `IntegerValue` | `0` | simulated 驱动零值（`register-1` 从未被写） |
| `Channel-1_timestamp` | `LongValue` | 读取时刻毫秒 | `timestamp.mode=PER_CHANNEL` |
| `Channel-1_unit` | — | 不出现 | 通道 `+unit` 为空串时跳过 |
| `Channel-1_error` | — | 不出现 | `emit.errors=false` |
| `assetName` | `StringValue` | `"s1"` | `WireAsset.java:128` |

- `emit.on.change=false` ⇒ 不做值变化过滤（`ValueChangeCache`），每拍都发。
- `emit.empty.envelopes=true` ⇒ 即使属性为空也会发；本图属性非空，两种配置下都会发。
- 随后 `emitContext.emit(new WireEnvelope("s1", [record]))`（`WireAsset.java:132`）→ 路由 `s1 → Logger`，
  再次经 `DeliveryDispatcher#submit("Logger", …)` 投递。**信封的 emitterPid 是 `s1`，不是 `Timer`。**

### 步骤 8 · Logger 记录 + SSE 到页面

```java
// Logger#onWireReceive (:74)
final String receivedFrom = "Received WireEnvelope from " + wireEnvelope.getEmitterPid();   // :76
LOG.info("Received WireEnvelope from {}", wireEnvelope.getEmitterPid());                     // :77 SLF4J
this.logBuffer.append(this.pid, receivedFrom);                                               // :78 供 SSE
if (LoggingVerbosity.VERBOSE.name().equals(getLoggingLevel())) { …逐条打印 key : value… }    // VERBOSE 分支
```

本图 `log.verbosity=QUIET`（`Logger.java:27` 的属性 key）⇒ **每 2 秒只有一行**：

```
Received WireEnvelope from s1
```

- `QUIET` 下不打印记录内容，`TIMER` 的毫秒值、`Channel-1=0` 都不在日志里；改 `VERBOSE` 并保存可看到
  （属性变化会重建 Logger 实例）。
- 日志里是 **`from s1`** 而不是 `from Timer`：Logger 的上一跳是 WireAsset。
- 同一行既进 SLF4J（IDEA 控制台/标准输出），也进内存环形缓冲。

缓冲与推送：

| 组件 | 行为 | 代码 |
|---|---|---|
| `ComponentLogBuffer` | 按 PID 一个 `ArrayDeque`，容量 100，超容量丢最旧；`AtomicLong` 全局单调序号（游标即 `tail` 的 sinceSeq） | `CAPACITY:33`、`append:62`、`tail:87` |
| `WireComponentLogController` | `GET /api/v1/wires/graph/components/{pid}/log/stream`（SSE，`text/event-stream`，需 `wires.admin`） | `:50`、`:51` |
| `ComponentLogStreamer` | 独立单守护线程 `wire-log-sse`，每 200 ms 拉一次增量；每连接各自记 `since`，空闲 15 s 心跳、最长 30 min | `PUSH_INTERVAL_MILLIS:44`、`EMITTER_TIMEOUT_MILLIS:50`、`subscribe:96`、`pushPending:125`、`newScheduler:171` |

事件名 `log`，`data` 为 `{seq, timestamp, text}`；前端「日志详情」面板就是这个端点，效果等价 `tail -f`，
最多回放最近 100 行。写端在**投递线程**、读端在 SSE 线程，写端不做任何网络 IO（RFC 007 的约束）。

```bash
# 本机确认正在跑（每 2 秒应出现一条 text="Received WireEnvelope from s1"）
# 默认账号在 application.yml 的 admin.* 下，口令可用 KURA_EDGE_ADMIN_PASSWORD 覆盖
curl -N -u admin:changeme -H 'Accept: text/event-stream' \
  'http://127.0.0.1:8080/api/v1/wires/graph/components/Logger/log/stream?since=0'
```

---

## 4. 线程模型

| 线程 | 谁创建 | 在这条链上干什么 |
|---|---|---|
| `wire-graph-scheduler`（单守护线程） | `ScheduledExecutorTickScheduler#newSingleThreadScheduler:104` | CRON 排程 + `Timer#tick()` + 投递；**不读驱动、不写日志文件** |
| `wire-envelope-delivery-*`（有界池，默认 `max(4, CPU×2)`） | `WireGraphEngine#newDeliveryExecutor:793` | `WireAsset#onWireReceive`（含驱动 read）、`Logger#onWireReceive` |
| 驱动（同 JVM，无独立线程） | Spring 容器（`@Component` 工厂） | 在调用方线程上被直接调用：`SimulatedDriver#read:107` |
| `wire-log-sse`（单守护线程） | `ComponentLogStreamer#newScheduler:171` | 每 200 ms 把 `ComponentLogBuffer` 增量推给 SSE |
| `http-nio-8080-exec-*`（Tomcat） | Spring Boot | 保存整图、SSE 握手、其余 REST |

时间线（一拍，t=0 为计划触发时刻）：

```
t=0ms      scheduler: runCronTick → tickIfCurrent → Timer#tick → dispatch → lane.submit   （返回）
t=0+ms     delivery : WireAsset#onWireReceive → readAllChannels → SimulatedDriver#read
t=0+ms     delivery : emit 信封 → dispatch → Logger 的 lane → Logger#onWireReceive（SLF4J + buffer）
t≈+200ms   wire-log-sse : pushPending 把新行推给已连接的页面
t=2000ms   scheduler : 下一拍（与上一拍的 read 是否结束无关）
```

同一接收组件的信封串行有序（lane），不同组件互不阻塞；单个组件异常不影响其它边与后续 tick。

---

## 5. 边界与坑（按出现频率排序）

1. **重启后图组件实例不重建**（第 2 节第 3 条）：库里有快照，但 `Timer`/`Logger` 实例不会自动建；
   再保存一次属性完全相同的图也不会重建（`propertiesChanged:728` 为假 ⇒ 不 `rebuildInstance`）。
   需要改一次运行期属性，或把组件删掉重加。
2. **排程失败是静默的**：CRON 表达式非法时 `nextCronFireTime` 抛异常，被 `scheduleInstance` 的 catch
   （`:595`）记成 ERROR 后跳过——图还在、实例还在，只是定时器不动。
3. **路由表为空 ⇒ 信封静默丢弃**：`EnvelopeRouter#dispatch:72` 用 `getOrDefault(emitterPid, List.of())`（`:73`），
   出边表没建好就不报错。表由 `refresh:58` 在实例创建/销毁时重建。
4. **`driver.pid` 错误能保存成功**：只有新建资产校验（`createAsset:165`），资产更新与图保存都不校验，
   第一次读才抛 `CHANNEL_IO_FAILED`。
5. **读失败不发错误信封**：`emit.errors=false`（本图）时只有 ERROR 日志，下游什么都收不到。
   诊断时看 `Error while performing read from the Wire Asset`（`WireAsset.java:100`）。
6. **定时器不等下游**：`Timer#tick` 只投递。read 慢的后果是 lane 排队/丢弃，不会反过来拖慢下一拍；
   队列与丢弃量用 `getDeliveryStats:243` 观测。
7. **不能改成事件/推送模式**：`Channel-1#+listen=false`，且驱动的 `registerChannelListener`
   （`SimulatedDriver.java:130`、`ModbusDriver.java:149`）只记 debug、不回调。
8. **`WIRE_RECORDS` 恒为 0**：只有 `DbWireRecordStore` 写该表，本图没有该节点（1.3 节）。

---

## 6. 观测与排查

### 6.1 预期日志（确认「确实走到了」）

| 观测点 | 预期 | 位置 |
|---|---|---|
| 保存整图时 | `Scheduled wire component Timer for periodic ticks: false` 一次 | `WireGraphEngine.java:594` |
| 每 2 秒 | `Received WireEnvelope from s1` 一行（QUIET） | `Logger.java:77` |
| 日志面板 | 每 2 秒一条 `{seq,timestamp,text}`，最多 100 行 | SSE 端点 |
| 单次 tick 异常 | `Scheduled tick for wire component Timer failed`（ERROR，链不中断） | `ScheduledExecutorTickScheduler.java:99` |
| 排程失败 | `Failed to schedule wire component Timer: …`（ERROR） | `WireGraphEngine.java:595` |
| 接收端异常 | `Component s1 failed to handle wire envelope`（WARN） | `EnvelopeRouter.java:89` |
| 读驱动异常 | `Error while performing read from the Wire Asset: s1`（ERROR，不发错误信封） | `WireAsset.java:100` |
| `WIRE_RECORDS` | 0 行（本图无 DbStore） | 1.3 节 |

频率核对：`cron.interval=0/2 * * * * ?` ⇒ 每 2 秒一次 `Timer#tick` ⇒ 每次 tick 一次资产读 ⇒ 一次 Logger 记录。
「每 2 秒一行」即说明 `Timer → s1 → Logger` 三段全通；「一次都不动」按 6.2 排查。

### 6.2 排查表

| 现象 | 先看 | 原因与修法 |
|---|---|---|
| 没有 `Scheduled wire component …` 日志 | `scheduleInstance:583` 是否被调用 | ①实例没创建（保存被 4xx 拦下：环/端口/工厂校验）②组件不是 `ScheduledEmitter`（`service.factoryPid` 写错）③**重启后实例未重建**（第 5 节第 1 条） |
| 有 `Scheduled …` 但无下游日志 | `cron.interval` 与 `isPeriodic()` | `type=CRON` 时 `cron.interval` 必须是合法 Spring CRON 表达式，否则抛异常被记 ERROR 并跳过排程 |
| 定时器在跑、`Logger` 无输出 | 路由表与资产读 | ①出边表为空 ⇒ 信封静默丢弃（`EnvelopeRouter:73`）②资产读抛 `CHANNEL_IO_FAILED`（`driver.pid` 不存在 / 工厂没注册 / 通道被禁用），且不发错误信封 |
| 收不到但实例都在 | `routesByEmitter` 的 key | key 是连线里的 `emitter` PID（`EnvelopeRouter.java:40`、`:58`），必须与组件 `getPid()` 一致 |
| 日志显示 `from Timer` 而非 `from s1` | 图里是否把 Timer 直连 Logger | `from` 打印的是信封 emitterPid；本图 Logger 上一跳是 WireAsset |
| 看不到记录内容（`Channel-1 : 0`） | `log.verbosity` | `QUIET` 只记来源；改 `VERBOSE` 并保存后重建 Logger 实例 |
| 改了 CRON 表达式仍是旧节奏 | 世代号与重建 | 属性变化才重建实例（`propertiesChanged:728`）；坐标/端口名变化被 `runtimeProperties:745` 排除，故意不重建 |
| 手动 `_read` 有值但日志没动 | 是否走了图 | 手动读不经图与定时器，不产生信封，不进 Logger（步骤 6.4） |
| `WIRE_RECORDS` 一直是 0 | 图里有没有 DbStore | 只有 `DbWireRecordStore` 写该表；本图没有 |

---

## 7. 上游（OSGi）对照

| 上游（Eclipse Kura） | 本项目 |
|---|---|
| `WireGraphServiceImpl` + ConfigurationAdmin + WireAdmin | `WireGraphEngine` + `SnapshotStore`/`ComponentConfigurationService`（JPA + H2/PostgreSQL） |
| `SimpleTimerExecutor` / `CronTimerExecutor`（组件内自建调度） | `ScheduledEmitter` SPI + `ScheduledExecutorTickScheduler`（**引擎**调度，组件只暴露 `tick()`） |
| `WireAsset`（Producer + Consumer 双角色） | 同名 `WireAsset`，实现 `WireReceiver` + `SelfConfiguringComponent` |
| OSGi 驱动服务（同 JVM 内直接调用） | `DriverService` + `@Component` 驱动工厂（同 JVM 内直接调用，与上游一致） |
| `Logger`（Felix 日志服务） | `Logger` → SLF4J + `ComponentLogBuffer` → SSE（RFC 007） |
| 快照 = ConfigurationAdmin snapshot + `WireGraph` 字符串属性 | 快照 = `WIRE_GRAPH_SNAPSHOT.GRAPH_JSON`（字段名与上游一致） |

---

## 8. 代码位置索引

### 8.1 按步骤

| 步骤 | 入口 | 关键位置 |
|---|---|---|
| 0 启动 | `DriverServiceImpl#init:74` | `reloadAll:197`（`createInstance:220`）、`WireGraphEngine` 构造器 `:215/:219/:225`、`loadGraph:492`、`withComponentConfigurations:512`、`refreshRoutes:529` |
| 1 挂排程 | `WireGraphEngine#scheduleInstance:583` | 监听器 `:219`、`notifyCreated:254`、`createInstance:138`、排程异常 `:595` |
| 2 算下一拍 | `scheduleNextCronTick:601` | `scheduleCronTickAfter:613`、`isCurrentGeneration:724`、`nextCronFireTime:143`、`scheduleOnce:63`、`delayMillisCeil:703` |
| 3 到点发射 | `runCronTick:648` | `awaitFireTime:678`（`MAX_IN_PLACE_WAIT_NANOS:83`）、`tickIfCurrent:708`、续排 `:659`、`nextGeneration:720`、`Timer#tick:67` |
| 4 路由投递 | `WireGraphEngine#dispatch:533` | `EnvelopeRouter#dispatch:72`、`getOrDefault:73`、`submit:80`、`deliver:80`、`Lane#submit:140`、`drainLoop:184`、`newDeliveryExecutor:793` |
| 5 资产接信封 | `WireAsset#onWireReceive:83` | `emitAllReadChannels:93`、`readAllChannels:90`、`getAllReadRecords:187`、`determineWritingChannels:140`、错误日志 `:100` |
| 6 读驱动 | `BaseAsset#requireDriver:225` | `findDriver:116`、`SimulatedDriver#read:107`、`zeroValueOf:203` |
| 7 组装信封 | `WireAsset#emitChannelRecords:114` | `RecordFillers#toEnvelopeProperties:37`、`assetName` `:128`、`emit:132` |
| 8 日志 | `Logger#onWireReceive:74` | `LOG.info:77`、`append:78`、`ComponentLogBuffer:33/62/87`、`subscribe:96`、`pushPending:125` |

### 8.2 按文件

| 文件 | 本流程相关位置 |
|---|---|
| `kura-edge-core/.../engine/WireGraphEngine.java` | `MAX_IN_PLACE_WAIT_NANOS:83`、`instanceGenerations:108`、`setEnvelopeDispatcher:215`、监听器 `:219`/`:225`/`:228`、`"Wire graph engine started":235`、`getDeliveryStats:243`、`update:293`、`delete:316`、`@PreDestroy:348`、`shutdown:349`、`validate:355`、`validateFactories:371`、`apply:381`、`propertiesChanged:728`、`rebuildInstance:774`、`withComponentConfigurations:512`、`loadGraph:492`、`updateGraphCache:524`、`refreshRoutes:529`、`dispatch:533`、`scheduleInstance:583`、`scheduleNextCronTick:601`、`scheduleCronTickAfter:613`、`runCronTick:648`、`awaitFireTime:678`、`delayMillisCeil:703`、`tickIfCurrent:708`、`nextGeneration:720`、`isCurrentGeneration:724`、`runtimeProperties:745`、`newDeliveryExecutor:793` |
| `kura-edge-core/.../engine/ScheduledExecutorTickScheduler.java` | `schedulePeriodic:53`、`scheduleOnce:63`、`cancel:71`、`cancelAll:80`、`shutdown:88`、`guarded:93`（ERROR `:99`）、`newSingleThreadScheduler:104` |
| `kura-edge-core/.../engine/EnvelopeRouter.java` | `routesByEmitter:40`、`refresh:58`、`dispatch:72`、`deliver:80`（WARN `:89`） |
| `kura-edge-core/.../engine/DeliveryDispatcher.java` | `submit:80`、`stats:90`、`removeLane:104`、`Lane:128`、`Lane#submit:140`、丢弃判定 `:143`、`drainLoop:184`、丢弃告警 `:216` |
| `kura-edge-core/.../engine/DeliverySettings.java` | `threads:26` / `queue-capacity:29` / `receiver-queue-capacity:32` |
| `kura-edge-core/.../registry/WireComponentRegistry.java` | `setEnvelopeDispatcher:82`、`addListener:112`、`createInstance:138`、`destroyInstance:189`、`notifyCreated:254` |
| `kura-edge-core/.../driver/DriverServiceImpl.java` | `@PostConstruct:73`/`init:74`、`getChannelDescriptor:98`、`findDriver:116`、`createDriver:150`、`reloadAll:197`、`isDriverConfig:211`、`factoryPidOf:215`、`createInstance:220` |
| `kura-edge-core/.../asset/AssetServiceImpl.java` | `@PostConstruct:68`/`init:69`、`reloadAll:167`、`getChannelAdsForDriver:201` |
| `kura-edge-core/.../asset/BaseAsset.java` | `DRIVER_NOT_ATTACHED:47`、`readAllChannels:90`、`getAllReadRecords:187`、`requireDriver:225`（抛错 `:228`/`:231`） |
| `kura-edge-core/.../asset/AssetChannelParser.java` | `parse:46`（读 `driver.pid` 等属性）、`withChannelDefaults:80` |
| `kura-edge-components/.../timer/Timer.java` | `tick:67`、`isPeriodic:80`、`getPeriodMillis:111`、`getInitialDelayMillis:125`、`nextCronFireTime:143` |
| `kura-edge-components/.../timer/TimerOptions.java` | `isSimple:71`、`getSimpleInterval:80`、`getCronExpression:114` |
| `kura-edge-components/.../timer/TimerFactory.java` | `create:88`（SIMPLE 参数校验） |
| `kura-edge-components/.../asset/WireAsset.java` | `onWireReceive:83`、`emitAllReadChannels` 调用 `:87`/定义 `:93`、`emitChannelRecords` 调用 `:98`/定义 `:114`、错误日志 `:100`、`emit:132`、`determineWritingChannels:140` |
| `kura-edge-components/.../asset/RecordFillers.java` | `toEnvelopeProperties:37` |
| `kura-edge-components/.../logger/Logger.java` | `PROP_LOG_LEVEL:27`、`onWireReceive:74`、`LOG.info:77`、`logBuffer.append:78` |
| `kura-edge-components/.../logger/ComponentLogBuffer.java` | `CAPACITY:33`、`append:62`、`tail:87` |
| `kura-edge-components/.../db/DbWireRecordStore.java` | `maximum.table.size:42`、`cleanup.records.keep:45`、`onWireReceive:89`、`cleanupIfNeeded:112`（本图未使用） |
| `kura-edge-components/.../driver/simulated/SimulatedDriverFactory.java` | `FACTORY_PID:28`、`getChannelDescriptor:48`、`create:53` |
| `kura-edge-components/.../driver/simulated/SimulatedChannelDescriptor.java` | `REGISTER_PARAM:25`、`DEFAULT_REGISTER:28`、Ad 定义 `:38` |
| `kura-edge-components/.../driver/simulated/SimulatedDriver.java` | `connect:81`、`read:107`、`registerOf:145`、`zeroValueOf:203` |
| `kura-edge-components/.../driver/modbus/ModbusDriverFactory.java` | `FACTORY_PID:32`、`getChannelDescriptor:65`、`create:70` |
| `kura-edge-components/.../driver/modbus/ModbusDriver.java` | `connect:77`、`read:107`、`dropConnection:202` |
| `kura-edge-rest/.../service/WireGraphSnapshotService.java` | `updateSnapshot:106`、`wireGraphService.update:196` |
| `kura-edge-rest/.../controller/WireGraphController.java` | `PUT /api/v1/wires/graph/snapshot:76` |
| `kura-edge-rest/.../service/ComponentLogStreamer.java` | `PUSH_INTERVAL_MILLIS:44`、`EMITTER_TIMEOUT_MILLIS:50`、`subscribe:96`、`pushPending:125`、`newScheduler:171` |
| `kura-edge-rest/.../controller/WireComponentLogController.java` | `GET /{pid}/log/stream:50`（`wires.admin` `:51`） |
| `kura-edge-rest/.../controller/AssetDataController.java` | `GET /{pid}/_read:73`、`POST /{pid}/_read:87`（人工读，不经图与定时器） |
| `kura-edge-rest/.../service/AssetDataService.java` | `readAll:77`、`read:95` |
| `kura-edge-rest/.../service/DriversAssetsService.java` | `createAsset:164`（driver 校验 `:165`）、`updateAsset:196`（不校验） |
| `kura-edge-api/.../model/asset/AssetConstants.java` | `ASSET_DRIVER_PROP:32` |
| `kura-edge-api/.../engine/ScheduledEmitter.java` | 排程 SPI：`isPeriodic()` / `tick()` / `nextCronFireTime` / `getPeriodMillis` |
| `kura-edge-rest/src/main/resources/application.yml` | `url:12`（文件模式 H2 + `AUTO_SERVER=TRUE`） |

---

## 附录 A：重新导出当前库（核对本文数据用）

`kuraedge.lock.db` 不能直接用 `Script` 工具打开（它只是文件锁）。把数据文件复制出来再导：

```bash
mkdir -p /tmp/kd && cp /home/idea/.kura-edge/data/kuraedge.mv.db /tmp/kd/
H2=$(find ~/.m2 -name "h2-2.*.jar" ! -name "*sources*" ! -name "*javadoc*" | sort | tail -1)
java -cp "$H2" org.h2.tools.Script \
  -url "jdbc:h2:file:/tmp/kd/kuraedge;IFEXISTS=TRUE" -user sa -password "" \
  -script /tmp/kd/schema.sql
grep -A2 'WIRE_GRAPH_SNAPSHOT\|COMPONENT_CONFIGURATION\|WIRE_RECORDS' /tmp/kd/schema.sql
```

若应用正在运行且数据仍被占用，复制 `.mv.db` 前先确认文件已落盘（H2 默认自动提交）；必要时停服后再导。
