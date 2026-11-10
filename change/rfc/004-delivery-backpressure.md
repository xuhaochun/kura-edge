# RFC 004 — 投递并发模型：有界线程池 + 每接收组件串行 + 丢弃计数

- 状态：**已批准（按用户「按照你的建议改」的要求执行方案 A+B，C 暂不做，D 作验收项）**
- 提出：2026-11-02
- 档位：**M**（图引擎投递路径重写 + 2 个文件以上 + 新增运行期语义「队列满则丢弃」）
- 相关规则：`.dsh/rules/08-additional-rules.md` §3（图更新原子性）、§5（线程模型与异常隔离）；
  `.dsh/rules/06-testing.md`（Gherkin + 覆盖率 core ≥ 85%）

## 0. 结果意象（用户视角，先于代码）

**现状**：定时器 1 秒一发、采集器 2 秒处理完时，稳态是 2 个投递线程且不会无限涨（Little's law）；
但投递线程池是 `Executors.newCachedThreadPool`——**线程数无上限、无队列、无拒绝策略、无任何指标**。
一旦某个组件挂死（驱动读无超时、socket 阻塞、锁死），线程按到达速率无界增长，几小时内 OOM；
同时同一组件实例会被多个线程并发重入（实测同一 `WireAsset` 同时有 3 个线程在 `onWireReceive` 里）。

**之后**：

- 投递线程数有硬上限（默认 `max(4, CPU×2)` 个），共享队列有界（默认 512）。
- **同一个接收组件实例任何时刻只有一个线程在跑**（per-pid 串行），慢组件只堵自己那条边，不连带其它分支；
  同一组件的信封处理天然有序。
- 该组件排队满时**丢弃新信封**并计数 + 限流 WARN（`Receiver <pid> queue is full ...`）；投递池满时
  排空任务推迟、计入 `rejectedDrains`（信封不丢，留在队列等下一次投递）。线程数与内存不再随积压增长。
- 对外提供 `DeliveryStats` 读数（`WireGraphEngine#getDeliveryStats()`）；图、快照 JSON、REST 契约全部不变。
- **不包含**（留给后续任务）：发射端按 emitter 限流/丢帧（方案 C）、Micrometer/Actuator 指标端点、
  驱动读写超时的具体参数调整（方案 D 只作为验收项写进 DoD：驱动调用必须在有限超时内返回）。

## 1. 问题

1. `WireGraphEngine.newDeliveryExecutor()` 用 `newCachedThreadPool`，`EnvelopeRouter.dispatch()` 对每条出边
   `executor.execute(deliver)`：到达速率 > 处理速率时线程数 = 速率 × 时长，处理挂死即无界增长。
2. 无队列、无拒绝策略 ⇒ 没有背压也没有可观测性；线程池状态完全黑盒。
3. 同一接收实例被并发重入（`WireAsset` 除 `ValueChangeCache` 外无锁），通道读写与 emit 顺序都可能交错。
4. `Fifo` 的 `drain()` 也是 `executor.execute(...)`，与 receive 之间没有顺序保证，且额外占一个池线程。

## 2. 方案

### 方案 A（采纳）：有界共享池 + 有界共享队列 + 计数与限流告警

- `ThreadPoolExecutor(core=max=threads, keepAlive=60s, ArrayBlockingQueue(queueCapacity))`，
  守护线程命名 `wire-envelope-delivery`，拒绝策略 `AbortPolicy`（由调用方捕获并计入丢弃）。
- 默认值：`threads = max(4, availableProcessors × 2)`、`queueCapacity = 512`，
  可用系统属性覆盖：`wires.engine.delivery.threads`、`wires.engine.delivery.queue-capacity`。

### 方案 B（采纳）：per-receiver 串行执行器（actor 风格）

- 每个接收 PID 一个 lane：`Deque<Runnable>` + `draining` 标记；`submit` 入队后若该 lane 空闲，
  只向共享池提交**一个**排空任务，排空任务串行执行该 lane 的全部信封；
- lane 容量默认 256（`wires.engine.delivery.receiver-queue-capacity`），满则丢弃新信封、计数、限流 WARN；
- 共享池拒绝（池/队列满）时丢弃该 lane 的排空任务并计数、`draining` 复位（下次 submit 会重新排空）。

### 方案 B 补充（实现期新增，评审后补入决策）：lane 随实例销毁回收

- `onInstanceDestroyed` → `DeliveryDispatcher#removeLane(pid)`：重建替换、从图上删除、整图删除都会走到，
  排队中的旧实例信封不会投给已销毁对象；整图删除额外 `removeAllLanes()`。
- 这部分信封随组件一起作废，**不计入** `dropped()`（无投递目标，与「处理不过来」的丢弃区分开）。

### 方案 C（本轮不做）：发射端丢弃/背压

保留到需要时（例如 Timer 允许丢帧），本轮不改 `ScheduledEmitter` 契约。

### 方案 D（验收项，不写代码）：组件不得无限阻塞

驱动读写必须带超时；`onWireReceive` 内禁止同步重试。已在 DoD 里列出检查点。

## 2.1 上游基线更正（评审后补）

上游投递层**不是**「每个 consumer 一条无界队列」——那是错误描述。证据：`kura-wires/bundles/pom.xml:43`
钉定 `equinox.wireadmin.version=1.0.800`，其 sources jar 在 `kura/.mvn-repo/.../org.eclipse.equinox.wireadmin-1.0.800-sources.jar`：

- `WireImpl#update(...)`（`WireImpl.java:144`，`synchronized`）→ `:193` `consumer.updated(this, value)`：
  **发布者线程内同步直调**，全文无队列、无 Executor；`WireSupportImpl#emit/updated`（`:122-128`、`:157-169`）同样同步。
- 因此上游的「背压」= 慢 consumer 阻塞发布者（含定时器线程），不丢数据；本项目异步投递 + 有界排队 + 丢弃
  是**已决偏离**，理由是把慢组件隔离出调度线程（rules/08 §5）。
- 上游只保证 per-`Wire` 串行（锁在 Wire 对象上，`:144`），同一 consumer 的多 Wire 仍可并发重入；
  本实现 per-receiver-PID 串行更严格。
- 上游异常兜底是 `catch (Throwable)` 后通知监听器（`WireImpl.java:192-200`）——本实现 `drainLoop` 同样兜 `Throwable`
  并复位 lane，`EnvelopeRouter#deliver` 兜 `RuntimeException`（组件级异常隔离）。

## 3. 兼容性与影响面

- 快照 JSON、REST 契约、组件配置键：**不变**（Rules 08：快照只能追加）。
- `Fifo` 的 `drain()`：由「异步提交到投递池」改为「在同一 lane 内联执行（receive 之后）」——
  仍与 receive 串行，且不再额外占池线程，顺带修掉旧实现「两次异步 drain 可能乱序」的问题。
  **语义收窄（评审后更正，原写「对外行为不变」不成立）**：同 lane 串行后 `Fifo` 的内部队列恒为 0/1，
  缓冲实际上由引擎 lane 承担，`queue.capacity` / `discard.envelopes` 只在「一次 drain 之前已排多个信封」时生效；
  上游 `Fifo`（独立消费线程 + 默认 50 条缓冲、满时阻塞生产者）的缓冲语义在此模型下不可达。
  若要恢复真实缓冲，需要让 `Fifo` 拥有独立消费线程——与 rules/08 §5「组件不自建线程」冲突，另开 RFC。
- 线程池现在是 `ExecutorService` 且由引擎持有：`shutdown()` 时 `shutdownNow()`；
  测试注入 `Runnable::run` 时 lane 排空任务同步执行，保持既有同步语义（既有测试不用改）。
- 丢弃语义：**新到的信封被丢弃**（已入队的不会被挤出），可观测、可计数；不做阻塞式背压
  （阻塞调度线程会改变 tick 节奏，且会把慢组件的影响扩散到整图）。

## 4. 测试 seam（`.dsh/rules/06-testing.md`：M 级须预先约定）

1. 单元（`DeliveryDispatcherTest`，不依赖时钟）：
   - 同 pid 并发上限恒为 1，且投递顺序 = 提交顺序；
   - 不同 pid 可并行（慢组件不阻塞另一组件）；
   - lane 满 → 丢弃计数递增、已入队信封仍被完整处理；
   - 池拒绝（用会抛 `RejectedExecutionException` 的 fake executor）→ `rejectedDrains` 递增、`dropped()` 不变、
     `draining` 复位后下一次投递把排队的信封全部补投；
   - `removeLane(pid)` → 该 lane 排队中的信封不再投递，新提交走新 lane。
2. 引擎级集成（`WireGraphEngineDeliveryTest`，真实调度器 + 真实投递池，参数以测试为准）：
   - 100ms tick / 120ms 处理 / 观察 900ms：同时进入接收器的线程数恒为 1、投递数 ≥ 3、无丢弃；
   - 20ms tick / 250ms 处理 / lane 容量 1 / 单线程池 / 观察 1000ms：`droppedByReceiverQueue > 0` 且 `delivered < submitted`。
3. CRON 排程（同批改动，原 RFC 未列，评审后补约定）：
   - `WireGraphEngineCronCadenceTest`（真实调度器 + 量化 100ms 假 CRON）：同拍只发一次、tick 落在计划整拍、不早发；
   - `WireGraphEngineResaveHandoverTest`（假调度器 + `MutableClock`）：替换实例后旧 CRON 链不再 tick/续排、
     属性未变不重建实例；
   - `WireGraphSnapshotTimerRestartTest`（真实 Spring + H2）：二次保存后 Timer 实例被替换且周期为新值。
   - 判据来自上游 Quartz：`CronTriggerImpl#triggered` 用计划触发时刻推下一次（`:496-499`），misfire 只补一次（`:395-407`）。
4. 既有回归：`WireGraphEngineTest` / `WireGraphEngineSchedulingTest`（含 Fifo drain 用例）必须继续绿。

## 4.1 已知偏离与后续项（评审后补）

| 项 | 现状 | 处置 |
|---|---|---|
| 上游同步直调 → 本实现异步 + 丢弃 | 已决偏离（隔离慢组件） | 本 RFC |
| 上游 `Fifo` 独立线程 + 缓冲 | 语义收窄为 lane 排队 | 本文档 §3；恢复需另开 RFC |
| 上游 Quartz 允许剩余 ≤2ms 直接点火；本实现等到计划时刻 | 新偏离，仅影响日志时间戳形态（不再出现 `:49.999`） | 接受，记录在此 |
| 系统时钟跳变后重排 CRON 触发器（Quartz `CronTimerExecutor` 注册 ClockEvent） | 未实现（旧有遗漏，本轮未补） | 后续 RFC |
| `Fifo#drain` 首条异常会丢整批（上游逐条 catch） | 未改（组件级，旧有） | 后续 RFC |
| Modbus `transact()` 在 lane 线程内同步重试（最坏 3s/通道） | 有界阻塞，未移出接收路径 | 后续 RFC（降低 maxRetries / 移出重试） |

## 5. DoD

- [x] `DeliveryDispatcher` + `DeliverySettings` 落地，`EnvelopeRouter`/`WireGraphEngine` 接线。
- [x] 新增单元与集成测试，覆盖上节 4 条 seam；`mvn -o verify` 全绿（core 覆盖率 ≥ 85%）。
- [x] 方案 D 检查（驱动读取调用点，2026-11-02 核查；评审后按源码更正）：
  - `kura-edge-components/.../driver/modbus/ModbusProtocolDevice.java:125` `candidate.connect(addr, responseTimeoutMillis)`
    与 `:126` `candidate.setSoTimeout(...)`：**连接与读都有超时**，默认响应超时 1000ms（`:68`），可配范围 1–60000ms（`:101-102`）。
  - **但仍是有界同步阻塞**：`transact()`（`:318-336`）在调用线程内重试，`maxRetries` 默认 2（`:70`），
    最坏每通道 (1+2)×1000ms = 3s；调用链 `WireAsset#onWireReceive`（`:83`）→ `ModbusDriver#read`（`:107-113`）→ `readValue`（`:156`）
    都在这条 lane 上同步执行。属「有时限的阻塞」，不是无限挂死（原 DoD 文字写「默认 60000ms」「无 RTU 传输」均不准确，
    已更正：RTU-over-TCP 已实现，见 `:97`、`:516-523`）。
  - `kura-edge-components/.../driver/simulated/SimulatedDriver.java`：内存模拟寄存器，无阻塞 I/O。
  - 发布侧只有 `LogPublisherSink`（纯日志），仓库内无 HttpClient/socket 发布实现。
  - 结论：现有组件最坏 3s/通道返回，线程数因此**有界**（这正是本次要解决的问题）；「移出重试 / 降低 maxRetries」
    留作后续 RFC（见 §4.1 表）。
- [x] `DeliveryStats` 读数（`WireGraphEngine#getDeliveryStats()`）与默认参数已写入 `docs/wires-runtime-flow.md` §4.1.1。
- [ ] 提交前 `wires-code-review` 三轴评审（进行中）。
