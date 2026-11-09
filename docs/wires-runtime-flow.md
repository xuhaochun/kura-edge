# Wire Graph 运行期流程：CRON 定时器 → 驱动采集 → 日志记录

> 本文以**当前运行的库**为准：`/home/idea/.kura-edge/data/kuraedge.mv.db`。
> 同目录的 `kuraedge.lock.db` 只是 H2 的文件锁（内容是 `server=…:42713`、`hostName=…`、`method=file` 这类锁元数据），
> 业务数据全在 `.mv.db` 里；下文第 1 节的三张表就是它当前的实际内容。
>
> 与上一版不同：这里描述的不是 `frontend/examples/demo-wires-snapshot.json`，而是真正落库的那张图——
> **`Timer`（CRON，每 2 秒）→ `s1`（WireAsset 绑 simulated 驱动 `s`）→ `Logger`（QUIET）**。
> 行号以当前工作区代码为准，改代码后请同步更新。

---

## 0. 一句话答案

| 问题 | 答案 | 位置 |
|---|---|---|
| 谁把 CRON 定时任务挂到调度器上？ | `WireGraphEngine#scheduleInstance`（`isPeriodic()==false` 走 `scheduleNextCronTick`） | `kura-edge-core/.../engine/WireGraphEngine.java:540`、`:558` |
| 挂在哪一刻？ | 组件实例创建后由注册表回调 `onInstanceCreated` → `scheduleInstance` | `WireGraphEngine.java:213-230`（监听器）、`WireComponentRegistry#createInstance:138` |
| 真正排 JDK 任务的是谁？ | `ScheduledExecutorTickScheduler#scheduleOnce` → `ScheduledExecutorService.schedule(...)`（单守护线程 `wire-graph-scheduler`） | `ScheduledExecutorTickScheduler.java:63`、`:104` |
| 到点执行哪段业务代码？ | `WireGraphEngine#runCronTick` → `tickIfCurrent` → `Timer#tick()` | `WireGraphEngine.java:605`、`:665`；`Timer.java:67` |
| Timer 发出的信封怎么到 `s1`？ | `WireEmitContext#emit` → `WireGraphEngine#dispatch` → `EnvelopeRouter#dispatch` → `DeliveryDispatcher#submit("s1", …)` → lane 内 `deliver` → **`WireAsset#onWireReceive`** | `WireGraphEngine.java:490`；`EnvelopeRouter.java:72`、`:80`；`DeliveryDispatcher.java:80`；`WireAsset.java:83` |
| 谁真正去读驱动？ | `BaseAsset#readAllChannels` → `RemoteDriver#read` → `DriverHostClient`（HTTP JSON-RPC）→ `RpcDispatcher#read` → `DriverHostRegistry#driver("s")` → `SimulatedDriver#read` | `BaseAsset.java:90`、`:225`；`RemoteDriver.java:78`；`RpcDispatcher.java:141`；`DriverHostRegistry.java:81`；`SimulatedDriver.java:107` |
| `s1` 怎么知道用 simulated 驱动？ | 资产配置 `driver.pid=s`（驱动**实例** PID）→ `findDriver("s")`；`s` 那行的 `service.factoryPid=org.eclipse.kura.driver.simulated` → 宿主里的 `SimulatedDriverFactory` | `AssetConstants.ASSET_DRIVER_PROP:32`；`DriverServiceImpl.java:150`、`:250`、`:255`；`DriverHostRegistry.java:63`（详见 5.2） |
| 采集结果变成什么记录？ | `Channel-1 = IntegerValue(0)`、`Channel-1_timestamp = 当前毫秒`、`assetName = "s1"` | `RecordFillers#toEnvelopeProperties:37`；`WireAsset.java:128` |
| 日志是谁写的？ | `Logger#onWireReceive`：SLF4J 一行 + 写 `ComponentLogBuffer` | `Logger.java:74`；`ComponentLogBuffer#append:62` |
| 日志怎么到页面？ | `ComponentLogStreamer` 每 200 ms 拉一次增量，经 SSE 推送 | `ComponentLogStreamer.java:44`（间隔）、`:96`（订阅） |
| 为什么 `WIRE_RECORDS` 是空的？ | 只有 `DbWireRecordStore` 写这张表，而它**不在本图里** | `DbWireRecordStore#onWireReceive:89` |

---

## 1. 库里的图长什么样

### 1.1 `COMPONENT_CONFIGURATION`：4 行配置

| PID | FACTORY_PID | 本流程用到的关键属性 |
|---|---|---|
| `Timer` | `org.eclipse.kura.wire.Timer` | `type=CRON`、`cron.interval=0/2 * * * * ?`、`simple.interval=2`、`simple.time.unit=SECONDS`、`simple.first.tick.policy=DEFAULT` |
| `s1` | `org.eclipse.kura.wire.WireAsset` | `driver.pid=s`、`Channel-1#+enabled=true`、`Channel-1#+type=READ`、`Channel-1#+value.type=INTEGER`、`Channel-1#simulated.register=register-1`、`Channel-1#+listen=false`、`timestamp.mode=PER_CHANNEL`、`emit.empty.envelopes=true`、`emit.errors=false`、`emit.connection.errors=false`、`emit.on.change=false` |
| `Logger` | `org.eclipse.kura.wire.Logger` | `log.verbosity=QUIET` |
| `s` | `org.eclipse.kura.driver.simulated` | 驱动**实例**（PID `s`）；`properties.service.factoryPid=org.eclipse.kura.driver.simulated` 指向宿主里的 `SimulatedDriverFactory` |

> `Timer` 的 `simple.*` 在本图**不生效**：`TimerOptions#isSimple:71` 只认 `type=SIMPLE`，本图是 `CRON`，
> `Timer#isPeriodic:80` 因此返回 `false`，走逐次排程而不是 `scheduleAtFixedRate`。

### 1.2 `WIRE_GRAPH_SNAPSHOT`：1 行，拓扑的唯一权威

`SERVICE_PID = org.eclipse.kura.wire.graph.WireGraphService`，`GRAPH_JSON` 内容：

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

要点：

- 快照只承载**拓扑 + 渲染属性**（`pid`/端口数/坐标），不含业务属性；业务属性在 `COMPONENT_CONFIGURATION`，
  由图引擎启动时回填（`WireGraphEngine#withComponentConfigurations:469`）。
- 组件按 PID 串联：`Timer` → `s1` → `Logger`。`s1` 既是资产又是组件，PID 与 Drivers & Assets 页面的资产 PID 相同。

### 1.3 `WIRE_RECORDS`：0 行（符合预期）

该表只由 `DbWireRecordStore#onWireReceive:89` 写入（`WireRecordRepository`）。
本图末端是 `Logger`，**没有 DbStore 节点**，所以这张表永远是 0 行。
「日志记录」在本项目里走的是 SLF4J + `ComponentLogBuffer`（见第 6 节），不是这张表。

---

## 2. 启动期：谁先起来，谁最后起

> 下面的方块表达「谁在什么阶段做什么」，Spring Bean 之间的创建顺序并无硬性保证；
> 真正的硬约束只有两条：`DriverHostManager` 在 `ApplicationReadyEvent` 才拉宿主，图引擎构造器只恢复快照与路由。

```
Spring 创建 Bean
├─ WireComponentRegistry：注入全部 WireComponentFactory（Timer/Logger/WireAsset…）
├─ DriverServiceImpl#init @PostConstruct (:75) → reloadAll (:231)
│    └─ 此时 driver-host 的远程工厂还没注册 ⇒ 驱动 s 这一刻建不出来（跳过）
├─ AssetServiceImpl#init @PostConstruct (:69) → reloadAll (:167)
│    └─ 按 COMPONENT_CONFIGURATION 重建资产 s1（WireAsset 实例）
│         └─ registry.createInstance → 监听器 onInstanceCreated
│              └─ s1 不是 ScheduledEmitter ⇒ 不排程；只 refreshRoutes()
├─ WireGraphEngine 构造器
│    ├─ :212 registry.setEnvelopeDispatcher(this::dispatch)
│    ├─ :213 注册 onInstanceCreated / onInstanceDestroyed 监听器
│    └─ :231 loadGraph() → 读快照 + 回填组件配置 + refreshRoutes()
│         └─ **只建路由表，不创建 Timer / Logger 实例**
│         └─ 日志 "Wire graph engine started"
└─ DriverHostManager#startOnReady @EventListener(ApplicationReadyEvent) (:88-89 → start():108)
     ├─ 拉起驱动宿主（auto：IDE 里=同进程 DriverHostServer；打包后=独立 java -jar …/kura-edge-driver-host.jar）
     ├─ listFactories → driverService.registerFactory(new RemoteDriverFactory(...))（宿主工厂的适配器）
     └─ driverService.reloadAll() ⇒ 按 COMPONENT_CONFIGURATION 建驱动实例 s（RemoteDriver）就绪
```

三条结论：

1. **驱动与资产在启动时就恢复**（各自 `@PostConstruct` / `ApplicationReadyEvent` 里 `reloadAll`）。
2. **图组件实例（`Timer`、`Logger`）不是启动时创建的**，而是保存整图时由 `WireGraphEngine#update:250` 的
   `apply:338` 创建：`PUT /api/v1/wires/graph/snapshot` → `WireGraphSnapshotService#updateSnapshot:106`
   → `:196 wireGraphService.update(...)` → `registry.createInstance(...)` → 监听器 `scheduleInstance`。
3. **已知边界（重启语义）**：库是文件模式 H2（`kura-edge-rest/src/main/resources/application.yml:12`，
   `AUTO_SERVER=TRUE`），所以快照与组件配置重启后仍在、图不会「变空」；但**图里的组件实例不会自动重建**——
   引擎启动只恢复快照与路由表，`Timer`/`Logger` 的实例只由保存时的 `update` 创建。
   按当前代码，重启后再保存一次**属性完全相同**的图也不会重建实例——`apply` 的 retained 分支只在
   `propertiesChanged(...)` 为真时才 `rebuildInstance`。要让定时器重新跑起来，需要改一次该组件的运行期属性
   （触发重建）或把它从图上删除后重新加入。这是行为边界，不是本流程的预期工作点。

---

## 3. 阶段 1：CRON 定时器怎么挂上、怎么触发

### 3.1 挂排程：创建实例后的那次回调

```java
// WireGraphEngine#scheduleInstance (:540)
if (!(instance instanceof ScheduledEmitter emitter)) return;      // Logger / WireAsset 直接返回
if (emitter.isPeriodic()) {                                      // Timer: type=CRON ⇒ false
    tickScheduler.schedulePeriodic(pid, () -> tickIfCurrent(pid, emitter, generation),
            emitter.getInitialDelayMillis(), emitter.getPeriodMillis());
} else {
    scheduleNextCronTick(pid, emitter, generation);              // ← 本图走这条
}
log.info("Scheduled wire component {} for periodic ticks: {}", pid, emitter.isPeriodic());
```

- 回调来自 `WireComponentRegistry#notifyCreated`（注册表在 `createInstance` 末尾同步通知监听器）。
- 排程异常只记 `ERROR`、不阻断整图更新（`:552-555`），所以配置写错时表现为「图在、但定时器不动」。

### 3.2 「逐次排程」的 CRON 链

```
scheduleNextCronTick (:558)
└─ scheduleCronTickAfter(after = clock.instant()) (:570)
   ├─ isCurrentGeneration(pid, generation)?          // 世代号校验，旧实例的链到此为止
   ├─ next = emitter.nextCronFireTime(after)          // Timer.java:143
   │    └─ CronExpression.parse("0/2 * * * * ?").next(after 转 UTC ZonedDateTime).toInstant()
   └─ tickScheduler.scheduleOnce(pid, () -> runCronTick(pid, emitter, generation, next),
        delayMillisCeil(now, next))                   // :660，向上取整毫秒，绝不早于计划时刻
```

```
runCronTick (:605)
├─ awaitFireTime (:635)：剩余 ≤ 2 ms 就 LockSupport.parkNanos 原地等过去；
│                        剩余更多说明唤醒过早 ⇒ 重新排程，本次不发射（不占调度线程）
├─ tickIfCurrent → 世代号仍是当前实例 ⇒ emitter.tick()
└─ finally：scheduleCronTickAfter(after = max(now, fireAt))   // 单次失败也续排，且不会重复同一拍
```

设计动机（两个已修的坑，注释里写着）：

- `delayMillisCeil` 向上取整 + `awaitFireTime`：秒级 CRON 不能被提前不到 1 ms 唤醒，否则时间戳变成 `:49.999Z`。
- 续排起点用「本次计划触发时刻 `fireAt`」而不是 `Instant.now()`：用 `now` 会在提前唤醒时算回同一拍，导致同一拍发两次。

### 3.3 本图参数

| 项 | 值 | 说明 |
|---|---|---|
| `type` | `CRON` | 只读 `cron.interval`，`simple.*` 不参与 |
| `cron.interval` | `0/2 * * * * ?` | 每 2 秒（第 0、2、4… 秒）触发一次 |
| 触发频率 | 每 2 秒 1 次（0.5 Hz） | 与 `WIRE_RECORDS` 无关（那张表不走这条链） |
| 首次触发 | 保存后约 2 秒内 | 由 `nextCronFireTime(now)` 决定，落在整秒 |
| `Scheduled ...` 日志 | `Scheduled wire component Timer for periodic ticks: false` | 保存时打印一次，`false` 即「非 SIMPLE」 |

### 3.4 取消与世代号

- 实例销毁 → `onInstanceDestroyed`（`WireGraphEngine.java:222`）：先 `nextGeneration(pid)`，再
  `tickScheduler.cancel(pid)` → `deliveryDispatcher.removeLane(pid)` → `refreshRoutes()`。
- 世代号（`instanceGenerations`）保证「改配置保存」后旧实例的 CRON 链不会继续续排：
  `scheduleCronTickAfter:572` 与 `tickIfCurrent:665` 都先校验世代号。
  对应测试：`kura-edge-rest/src/test/java/com/kura/wires/rest/WireGraphSnapshotTimerRestartTest.java`。
- 引擎关闭：`@PreDestroy shutdown():305` → `tickScheduler.cancelAll()` + `shutdown()` + `deliveryDispatcher.close()`。

---

## 4. 阶段 2：信封从 Timer 到 s1

### 4.1 精确调用链（含线程切换）

```
wire-graph-scheduler（TickScheduler 单守护线程）
└─ WireGraphEngine#runCronTick → tickIfCurrent → Timer#tick()          Timer.java:67
   ├─ new WireRecord({"TIMER": LongValue(System.currentTimeMillis())})
   └─ emitContext.emit(new WireEnvelope("Timer", [record]))
        └─ WireGraphEngine#dispatch("Timer", envelope)                  :490
           └─ EnvelopeRouter#dispatch("Timer", envelope)                :72
              ├─ routesByEmitter["Timer"] = [wire(Timer→s1, port 0)]    （refresh:58 建表）
              ├─ registry.getInstance("s1") → WireAsset 实例
              └─ DeliveryDispatcher#submit("s1", () -> deliver(component, envelope))   :80
                   └─ 入 s1 的 lane 队列后**立刻返回**（不在这里读驱动）

wire-envelope-delivery-*（有界线程池）
└─ EnvelopeRouter#deliver(component, envelope)                           :80
   ├─ receiver.onWireReceive(envelope)  → WireAsset#onWireReceive        WireAsset.java:83
   └─ receiver instanceof DrainableComponent ? drain()（Fifo 专属；本图没有）
```

### 4.1.1 投递并发模型（RFC 004，摘要）

- 投递池**有界**：默认 `max(4, CPU×2)` 线程 + 共享队列 512，线程名 `wire-envelope-delivery`（`WireGraphEngine.java:750`）。
- 每个接收 PID 一条 **lane**（`DeliveryDispatcher.Lane`）：同 lane 串行有序，默认排队上限 256
  （系统属性 `wires.engine.delivery.receiver-queue-capacity`）。
- lane 满了丢**新**信封并计入 `DeliveryStats.droppedByReceiverQueue`（WARN 每 100 条限流一次）；
  池拒绝时该组件的排空任务推迟、计入 `rejectedDrains`（信封不丢）。
- 组件抛 `RuntimeException` 只被 `deliver` 的 catch 记 WARN（`EnvelopeRouter.java:88-90`），不影响其它边与后续 tick。

---

## 5. 阶段 3：s1 做一次驱动采集

### 5.1 调用链（本图每一拍都走一遍）

```
WireAsset#onWireReceive(envelope)                                        WireAsset.java:83
├─ ① emitAllReadChannels()                                               :93
│   └─ readAllChannels()                                                 BaseAsset.java:90
│      ├─ requireDriver()                                                :225
│      │   ├─ configuration.getDriverPid() = "s"                         ← 见 5.2
│      │   ├─ driverService.findDriver("s") → RemoteDriver（首次读时 connect()）
│      │   └─ 失败抛 WiresException(CHANNEL_IO_FAILED)
│      ├─ getAllReadRecords() → [ChannelRecord("Channel-1", INTEGER)]
│      │   └─ 通道配置随记录带上：{+type:READ, +value.type:INTEGER,
│      │        simulated.register:register-1, …}，unit = ""
│      ├─ RemoteDriver#read(records)                                    RemoteDriver.java:78
│      │   └─ DriverHostClient#read → HTTP JSON-RPC（loopback）
│      │        └─ RpcDispatcher#read                                   RpcDispatcher.java:141
│      │             └─ DriverHostRegistry#driver("s")                  DriverHostRegistry.java:81
│      │                  └─ SimulatedDriver#read                       SimulatedDriver.java:107
│      │                       ├─ registerOf(record) = "register-1"（通道参数 simulated.register）
│      │                       ├─ registers 里没有该寄存器 ⇒ zeroValueOf(INTEGER) = IntegerValue(0)  :203
│      │                       ├─ setChannelStatus(SUCCESS)
│      │                       └─ setTimestamp(System.currentTimeMillis())
│      └─ ScaleOffsetApplier.apply（scale=1.0 / offset=0.0 ⇒ 值不变）
└─ ② 对收到的每条记录判定是否写设备（本图是空转，见 5.4）
```

要点：

- 读驱动发生在**投递线程**（`wire-envelope-delivery-*`），不在 `wire-graph-scheduler` 上；
  调度线程只构造信封并投递。真正取数在驱动宿主里：`process` 模式是另一个 JVM，
  IDE 默认的 `in-process` 模式仍是主 JVM 内的 `DriverHostServer`（走 loopback HTTP，见 5.2 末尾）。
- `requireDriver` 是**懒连接**：第一次读才 `connect()`；连接异常会复位 `connected=false` 并包装成
  `WiresException(CHANNEL_IO_FAILED)`。
- 读失败时 `WireAsset#emitAllReadChannels` 只记 `ERROR`；本图 `emit.errors=false && emit.connection.errors=false`，
  因此**不会**发错误信封，表现为「定时器在跑但没有下游输出」。

### 5.2 `s1` 的 `driver.pid=s` 是怎么落到 simulated 驱动上的

图里 `s1` 只是一个 PID。它用哪个驱动**不在 `WIRE_GRAPH_SNAPSHOT` 里**，而在资产配置的 `driver.pid` 上；
从那里到 `SimulatedDriver` 是**两次独立查找**串起来的：

```
s1（资产/组件 PID）
 └─ COMPONENT_CONFIGURATION[s1].driver.pid = "s"                      ← 驱动「实例 PID」
     └─ DriverServiceImpl#findDriver("s")                             :150（instances: 实例PID → Driver）
         └─ COMPONENT_CONFIGURATION[s].service.factoryPid = "org.eclipse.kura.driver.simulated"
             └─ factories["org.eclipse.kura.driver.simulated"]
                 ├─ 主程序侧：RemoteDriverFactory（宿主工厂的适配器，只发 RPC）
                 └─ 宿主侧：SimulatedDriverFactory#create → new SimulatedDriver("s", …)
```

| 层 | 查找用的键 | 本库的值 | 代码 |
|---|---|---|---|
| ① 资产 → 驱动实例 | `driver.pid`（`AssetConstants.ASSET_DRIVER_PROP:32`） | `s` | `AssetChannelParser#parse:46` → `AssetConfiguration#getDriverPid` |
| ② 按实例 PID 取驱动 | 实例 PID | `s` | `BaseAsset#requireDriver:225` → `DriverServiceImpl#findDriver:150`（**只按 PID 查，不认 factoryPid**） |
| ③ 实例 → 工厂 | `COMPONENT_CONFIGURATION[s].service.factoryPid` | `org.eclipse.kura.driver.simulated` | `DriverServiceImpl#reloadAll:231` → `factoryPidOf:250` → `createInstance:255` |
| ④ 工厂（主程序） | factoryPid → `RemoteDriverFactory` | 宿主 `factory.list` 的 `DriverHostFactoryInfo` | `DriverHostManager#start:127-133`（listFactories→registerFactory→reloadAll） |
| ⑤ 实例创建（RPC） | `driver.create{pid:"s", properties}` | `properties` 来自第 ③ 步 | `RemoteDriverFactory#create:52` → `DriverHostClient#createDriver:100` |
| ⑥ 工厂（宿主） | **`properties.service.factoryPid`** | `org.eclipse.kura.driver.simulated` | `RpcDispatcher#create:132` → `DriverHostRegistry#create:63` |
| ⑦ 真正 new 出来 | `factory.create("s", properties)` | `new SimulatedDriver("s", properties)` | `SimulatedDriverFactory#create:51`、`SimulatedDriver.java:57` |

四个容易踩的点：

1. **`driver.pid` 是驱动实例 PID，不是 factoryPid。** `POST` 新建资产时 `DriversAssetsService#createAsset:165`
   会校验它存在；但**资产更新与图保存都不校验**（`updateAsset:196`、`WireGraphEngine#update:250` 只看组件 factoryPid），
   所以把错误值写进配置能保存成功，第一次读才炸：`findDriver` 查不到 ⇒
   `WiresException(CHANNEL_IO_FAILED, "Driver not attached")`（`BaseAsset.java:228`、`:231`），
   表现为「定时器在跑、Logger 没输出」。
2. **两侧都靠 `service.factoryPid` 对齐。** 主程序用它判定「这行配置是驱动配置」（`DriverServiceImpl#isDriverConfig`），
   宿主用它决定「用哪个工厂 new 实例」（`DriverHostRegistry#create:63`）。所以 `s` 那一行的
   `properties.service.factoryPid` 不能丢——这也是导入快照必须带组件配置、不能只导图 JSON 的原因。
3. **工厂是宿主注册的，不是 Spring 扫描的。** `SimulatedDriverFactory` 只在 `kura-edge-driver-host` 模块里，
   主程序没有这个类；主程序里注册的是适配器 `RemoteDriverFactory`。因此 `s` 能解析出来的前提是
   「宿主已起 → `factory.list` 拿回工厂 → `registerFactory` → `reloadAll`」，顺序见第 2 节。
4. **通道参数 `simulated.register` 也来自宿主描述符。** `SimulatedDriverFactory#getChannelDescriptor:46`
   → `SimulatedChannelDescriptor:25/28`（`REGISTER_PARAM` / `DEFAULT_REGISTER=register-1`）
   → `DriverHostRegistry#listFactories:49` → `DriverHostClient#listFactories:89`
   → `RemoteDriverFactory#getChannelDescriptor:47` → `DriverServiceImpl#getChannelDescriptor:144`
   → 保存资产时 `AssetServiceImpl#getChannelAdsForDriver:201` 取来，由 `AssetChannelParser#withChannelDefaults`
   回填 `Channel-1#simulated.register=register-1`；运行时 `SimulatedDriver#read:107` 用
   `registerOf(record)`（`SimulatedDriver.java:41`）读这个参数选寄存器。

> **宿主在哪跑？** `kura.driver.host.mode=auto` 时，从 `target/classes` 运行且宿主类在 classpath 上
> （IDE / `spring-boot:run`）⇒ **同进程**模式（`InProcessDriverHostLauncher`，`DriverHostServer` 起在主 JVM 里、
> 只 bind 回环地址，可用 `ss -ltnp | grep <主程序 pid>` 看到 8080 之外的那个 loopback 端口）；打成 fat jar 后宿主类被 repackage 排除 ⇒ **独立进程**
> （`java -jar ~/.kura-edge/drivers/kura-edge-driver-host.jar`）。两种模式下 `DriverHostClient` 都走
> HTTP JSON-RPC，区别只是对端在不在同一个 JVM；`effectiveMode` 的判定见 `DriverHostManager.java:299`。

### 5.3 本图实际产出的信封

`emitChannelRecords`（`WireAsset.java:114`）→ `RecordFillers#toEnvelopeProperties:37`：

| 信封属性 | 类型 | 值 | 来源 |
|---|---|---|---|
| `Channel-1` | `IntegerValue` | `0` | simulated 驱动零值（`register-1` 从未被写） |
| `Channel-1_timestamp` | `LongValue` | 读取时刻毫秒 | `timestamp.mode=PER_CHANNEL` |
| `Channel-1_unit` | — | 不出现 | 通道 `+unit` 为空串时跳过 |
| `Channel-1_error` | — | 不出现 | `emit.errors=false` |
| `assetName` | `StringValue` | `"s1"` | `WireAsset.java:128` |

- `emit.on.change=false` ⇒ 不做值变化过滤，每拍都发。
- `emit.empty.envelopes=true` ⇒ 即使属性为空也会发；本图属性非空，两种配置下都会发。
- 随后 `emitContext.emit(new WireEnvelope("s1", [record]))`（`WireAsset.java:132`）→ 路由 `s1 → Logger`，
  再次经 `DeliveryDispatcher#submit("Logger", …)` 投递。**注意信封的 emitterPid 是 `s1`，不是 `Timer`。**

### 5.4 为什么不会写设备

`WireAsset#onWireReceive` 第 ② 步把收到的 `TIMER` 记录与资产通道匹配（`determineWritingChannels:140`）：
只有 `WRITE` / `READ_WRITE` 通道参与，本图 `Channel-1#+type=READ` ⇒ 匹配结果为空 ⇒ `writeChannels` 空转。
即使把通道改成 `READ_WRITE`，记录里也没有名为 `Channel-1` 的属性（只有 `TIMER`），仍然不会写。

---

## 6. 阶段 4：Logger 记录 + 前端查看

### 6.1 `Logger#onWireReceive`（`Logger.java:74`）

```java
final String receivedFrom = "Received WireEnvelope from " + wireEnvelope.getEmitterPid();
LOG.info("Received WireEnvelope from {}", wireEnvelope.getEmitterPid());   // SLF4J
this.logBuffer.append(this.pid, receivedFrom);                             // 供 SSE 面板
if (LoggingVerbosity.VERBOSE.name().equals(getLoggingLevel())) { …逐条打印 key : value… }
```

本图 `log.verbosity=QUIET`（`Logger.java:27` 的属性 key），所以**每 2 秒只有一行**：

```
Received WireEnvelope from s1
```

- `QUIET` 下不打印记录内容，`TIMER` 的毫秒值、`Channel-1=0` 都不会出现在日志里；
  要看到记录内容，把 `log.verbosity` 改成 `VERBOSE` 并保存（属性变化会重建 Logger 实例）。
- 日志里是 **`from s1`** 而不是 `from Timer`：Logger 的上一跳是 WireAsset，信封由 s1 发射。
- 同一行既进 SLF4J（本地跑在 IDEA 控制台 / 标准输出），也进内存环形缓冲。

### 6.2 `ComponentLogBuffer`（`kura-edge-components/.../logger/ComponentLogBuffer.java`）

- 按 PID 一个 `ArrayDeque`，容量 `CAPACITY = 100`（`:33`），写端 `append(:62)` 加锁、O(1)，超容量丢最旧。
- 全局单调序号 `AtomicLong sequence`：跨组件、跨图重载不重置（`tail(:87)` 的游标就是它）。
- 写端在**投递线程**，读端在 SSE 推送线程；写端不做任何网络 IO（RFC 007 的约束）。

### 6.3 怎么看到这些日志

```
GET /api/v1/wires/graph/components/Logger/log/stream?since=0
    （Accept: text/event-stream，需要 wires.admin）
→ WireComponentLogController#streamLog            :50
→ ComponentLogStreamer#subscribe(pid, since)      :96
```

- 推送由独立单守护线程 `wire-log-sse` 每 **200 ms** 拉一次增量（`PUSH_INTERVAL_MILLIS:44`、`pushPending`），
  每个连接各自记 `since`，多页面互不消费；空闲 15 s 心跳、连接最长 30 min。
- 事件名 `log`，`data` 为 `{seq, timestamp, text}`（`ComponentLogLineDto`）。
- 前端「日志详情」面板就是这个端点，效果等价 `tail -f`，最多回放最近 100 行。

```bash
# 本机确认正在跑（每 2 秒应出现一条 text="Received WireEnvelope from s1"）
# 默认账号在 application.yml 的 admin.* 下，口令可用 KURA_EDGE_ADMIN_PASSWORD 覆盖
curl -N -u admin:changeme -H 'Accept: text/event-stream' \
  'http://127.0.0.1:8080/api/v1/wires/graph/components/Logger/log/stream?since=0'
```

### 6.4 为什么 `WIRE_RECORDS` 仍然 0 行

`DbWireRecordStore#onWireReceive:89` 是这张表唯一的写入口（先按 `maximum.table.size`（默认 10000）清理，
再逐条存 `emitterPid / receivedAt / JSON 值`，然后**把信封原样转发下游**）。
本图没有 DbStore 节点 ⇒ 表恒为 0 行。要落库，需要把 Data Store 组件加到 `s1 → Logger` 之间（或作为旁路节点）。

---

## 7. 线程模型（对照 Rules 08 §5）

| 线程 | 谁创建 | 在这条链上干什么 |
|---|---|---|
| `wire-graph-scheduler`（单守护线程） | `ScheduledExecutorTickScheduler#newSingleThreadScheduler:104` | 只做 CRON 排程 + `Timer#tick()` + 投递；**不读驱动、不写日志文件** |
| `wire-envelope-delivery-*`（有界池，默认 `max(4, CPU×2)`） | `WireGraphEngine#newDeliveryExecutor:750` | 执行 `WireAsset#onWireReceive`（含跨进程读）、`Logger#onWireReceive` |
| 驱动宿主（独立 JVM 或同进程） | `DriverHostManager#startOnReady:88`（`process` / `in-process` 两种 `DriverHostLauncher`） | `SimulatedDriver#read` 真正取数；同进程模式下仍在 loopback HTTP 上，只是与主程序同 JVM |
| `wire-log-sse`（单守护线程） | `ComponentLogStreamer#newScheduler:171` | 每 200 ms 把 `ComponentLogBuffer` 增量推给 SSE |
| `http-nio-8080-exec-*`（Tomcat） | Spring Boot | 保存整图、SSE 握手、其余 REST |

同一接收组件的信封串行有序（lane），不同组件之间互不阻塞；单个组件异常不影响其它边与后续 tick。

---

## 8. 预期观测（用来确认「确实走到了」）

| 观测点 | 预期 | 位置 |
|---|---|---|
| 保存整图时 | `Scheduled wire component Timer for periodic ticks: false` 一次 | `WireGraphEngine.java:551` |
| 每 2 秒 | `Received WireEnvelope from s1` 一行（QUIET） | `Logger.java:77` |
| 日志面板 | 每 2 秒一条 `{seq,timestamp,text}`，最多 100 行 | SSE 端点 |
| 单次 tick 异常 | `Scheduled tick for wire component Timer failed`（ERROR，链不中断） | `ScheduledExecutorTickScheduler.java:99` |
| 接收端异常 | `Component s1 failed to handle wire envelope`（WARN） | `EnvelopeRouter.java:89` |
| 读驱动异常 | `Error while performing read from the Wire Asset: s1`（ERROR，不发错误信封） | `WireAsset.java:100` |
| `WIRE_RECORDS` | 0 行（本图无 DbStore） | 1.3 节 |

频率核对：`cron.interval=0/2 * * * * ?` ⇒ 每 2 秒一次 `Timer#tick` ⇒ 每次 tick 一次资产读 ⇒ 一次 Logger 记录。
收到「每 2 秒一行」即说明 `Timer → s1 → Logger` 三段全通；收到「一次都不动」按第 9 节排查。

---

## 9. 排查表

| 现象 | 先看 | 原因与修法 |
|---|---|---|
| 没有任何 `Scheduled wire component …` 日志 | `WireGraphEngine#scheduleInstance` 是否被调用 | ①实例没创建（保存被 4xx 拦下：环/端口/工厂校验）②组件不是 `ScheduledEmitter`（`service.factoryPid` 写错）③**后端重启后图组件实例未重建**（见第 2 节末尾「已知边界（重启语义）」） |
| 有 `Scheduled …` 但没有任何下游日志 | `cron.interval` 与 `isPeriodic()` | `type=CRON` 时 `cron.interval` 必须是合法 Spring CRON 表达式，否则 `nextCronFireTime` 抛异常，被 `scheduleInstance` 记成 ERROR 并跳过排程 |
| 定时器在跑，`Logger` 无输出 | 路由表与资产读 | ①`EnvelopeRouter#refresh` 是否在实例创建后执行过（出边表为空则信封被静默丢弃，`EnvelopeRouter#dispatch:73`）②资产读抛 `CHANNEL_IO_FAILED`（驱动宿主没起 / `driver.pid=s` 不存在 / 通道被禁用），且 `emit.errors=false` 时**不会**发错误信封，只在 ERROR 日志里可见 |
| 收不到但实例都在 | `routesByEmitter` 的 key | key 是连线里的 `emitter` PID（`EnvelopeRouter.java:61`），必须与组件 `getPid()` 一致 |
| 日志显示 `from Timer` 而不是 `from s1` | 图里是否把 Timer 直接连到 Logger | 本图 Logger 的上一跳是 WireAsset；`from` 打印的是信封的 emitterPid |
| 看不到记录内容（`Channel-1 : 0`） | `log.verbosity` | `QUIET` 只记来源；改 `VERBOSE` 并保存后重建 Logger 实例 |
| 改了 CRON 表达式保存后仍是旧节奏 | 世代号与重建 | 属性变化才会重建实例（`propertiesChanged`）；坐标/端口名变化被 `runtimeProperties` 排除，故意不重建 |
| `WIRE_RECORDS` 一直是 0 | 图里有没有 DbStore | 只有 `DbWireRecordStore` 写该表；本图没有 |

---

## 10. 上游（OSGi）对照

| 上游（Eclipse Kura） | 本项目 |
|---|---|
| `WireGraphServiceImpl` + ConfigurationAdmin + WireAdmin | `WireGraphEngine` + `SnapshotStore`/`ComponentConfigurationService`（JPA + H2/PostgreSQL） |
| `SimpleTimerExecutor` / `CronTimerExecutor`（组件内自建调度） | `ScheduledEmitter` SPI + `ScheduledExecutorTickScheduler`（**引擎**调度，组件只暴露 `tick()`） |
| `WireAsset`（Producer + Consumer 双角色） | 同名 `WireAsset`，实现 `WireReceiver` + `SelfConfiguringComponent` |
| OSGi 驱动服务（同 JVM） | 驱动宿主独立进程，loopback JSON-RPC（RFC 006） |
| `Logger`（Felix 日志服务） | `Logger` → SLF4J + `ComponentLogBuffer` → SSE（RFC 007） |
| 快照 = ConfigurationAdmin snapshot + `WireGraph` 字符串属性 | 快照 = `WIRE_GRAPH_SNAPSHOT.GRAPH_JSON`（字段名与上游一致） |

---

## 11. 源码索引

| 文件 | 本流程相关位置 |
|---|---|
| `kura-edge-core/.../engine/WireGraphEngine.java` | 监听器 `:213-230`、`loadGraph:449`、`withComponentConfigurations:469`、`update:250`、`apply:338`、`dispatch:490`、`scheduleInstance:540`、`scheduleNextCronTick:558`、`scheduleCronTickAfter:570`、`runCronTick:605`、`awaitFireTime:635`、`delayMillisCeil:660`、`tickIfCurrent:665`、`newDeliveryExecutor:750`、`shutdown:305` |
| `kura-edge-core/.../engine/ScheduledExecutorTickScheduler.java` | `schedulePeriodic:53`、`scheduleOnce:63`、`cancel:71`、`guarded:93`、`newSingleThreadScheduler:104` |
| `kura-edge-core/.../engine/EnvelopeRouter.java` | `refresh:58`、`dispatch:72`、`deliver:80` |
| `kura-edge-core/.../engine/DeliveryDispatcher.java` | `submit:80`、`removeLane:104`、`Lane#submit:140` |
| `kura-edge-core/.../engine/DeliverySettings.java` | 三个系统属性：`wires.engine.delivery.threads` / `.queue-capacity` / `.receiver-queue-capacity` |
| `kura-edge-core/.../registry/WireComponentRegistry.java` | `setEnvelopeDispatcher`、`createInstance:138`、`destroyInstance`、`notifyCreated` |
| `kura-edge-core/.../driver/DriverServiceImpl.java` | `init:75`、`registerFactory:104`、`findDriver:150`、`getFactoryPid:160`、`getChannelDescriptor:144`、`reloadAll:231`、`factoryPidOf:250`、`createInstance:255` |
| `kura-edge-core/.../driver/remote/DriverHostManager.java` | `startOnReady:88-89` → `start:108`、工厂注册与 `reloadAll` `:127-133`、`effectiveMode:299` |
| `kura-edge-core/.../driver/remote/RemoteDriverFactory.java` | `factoryPid:37`、`getChannelDescriptor:47`、`create:52` |
| `kura-edge-core/.../driver/remote/DriverHostClient.java` | `listFactories:89`、`createDriver:100`、`read:141`、`write:152` |
| `kura-edge-core/.../driver/remote/RemoteDriver.java` | `read:78`（JSON-RPC + 结果回填） |
| `kura-edge-rest/.../config/InProcessDriverHostLauncher.java` | `mode:35`、`launch:40`（同 JVM 起 `DriverHostServer`） |
| `kura-edge-core/.../asset/AssetServiceImpl.java` | `init:69`、`reloadAll:167`、`getChannelAdsForDriver:201` |
| `kura-edge-core/.../asset/BaseAsset.java` | `readAllChannels:90`、`getAllReadRecords:187`、`requireDriver:225` |
| `kura-edge-core/.../asset/AssetChannelParser.java` | 通道属性 `Channel-1#+type` / `Channel-1#simulated.register` 的解析与 `withChannelDefaults` |
| `kura-edge-components/.../timer/Timer.java` | `tick:67`、`isPeriodic:80`、`getPeriodMillis:111`、`getInitialDelayMillis:125`、`nextCronFireTime:143` |
| `kura-edge-components/.../timer/TimerOptions.java` | `isSimple:71`、`getSimpleInterval:80`、`getCronExpression:114` |
| `kura-edge-components/.../timer/TimerFactory.java` | `create:88`（SIMPLE 校验） |
| `kura-edge-components/.../asset/WireAsset.java` | `onWireReceive:83`、`emitAllReadChannels:93`、`emitChannelRecords:114`、`emit:132`、`determineWritingChannels:140` |
| `kura-edge-components/.../asset/RecordFillers.java` | `toEnvelopeProperties:37` |
| `kura-edge-components/.../logger/Logger.java` | `onWireReceive:74`、`PROP_LOG_LEVEL:27` |
| `kura-edge-components/.../logger/ComponentLogBuffer.java` | `CAPACITY:33`、`append:62`、`tail:87` |
| `kura-edge-components/.../db/DbWireRecordStore.java` | `onWireReceive:89`（本图未使用） |
| `kura-edge-driver-host/.../RpcDispatcher.java` | `DRIVER_CREATE:38`、`create:132`、`read:141` |
| `kura-edge-driver-host/.../DriverHostRegistry.java` | `listFactories:49`、`create:63`（按 `properties.service.factoryPid` 找工厂）、`driver:81` |
| `kura-edge-driver-host/.../simulated/SimulatedDriverFactory.java` | `FACTORY_PID:26`、`getChannelDescriptor:46`、`create:51` |
| `kura-edge-driver-host/.../simulated/SimulatedChannelDescriptor.java` | `REGISTER_PARAM:25`、`DEFAULT_REGISTER:28` |
| `kura-edge-driver-host/.../simulated/SimulatedDriver.java` | `read:107`、`zeroValueOf:203` |
| `kura-edge-rest/.../service/WireGraphSnapshotService.java` | `updateSnapshot:106`、`wireGraphService.update:196` |
| `kura-edge-rest/.../service/ComponentLogStreamer.java` | `PUSH_INTERVAL_MILLIS:44`、`subscribe:96`、`pushPending`、`newScheduler:171` |
| `kura-edge-rest/.../controller/WireComponentLogController.java` | `GET /{pid}/log/stream:50`（`wires.admin`） |
| `kura-edge-rest/.../controller/WireGraphController.java` | `PUT /api/v1/wires/graph/snapshot:76` |

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
