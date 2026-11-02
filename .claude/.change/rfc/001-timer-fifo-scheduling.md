# RFC 001 — 引擎侧调度器（让 Timer/Fifo 真正跑起来）

- 状态：**待批准**（档位 L：引擎线程模型 + api 公共 SPI 变更）
- 提出：2026-10-17
- 相关规则：`.claude/rules/08-additional-rules.md` §3（图更新原子性）、§5（线程模型）、§9（许可证）

## 0. 结果意象（用户视角，先于代码）

**现状**：导入示例 → 保存 → 画布很漂亮，后端日志一片安静。`Timer.tick()` / `Fifo.drain()` 没有任何调用方，
图只是「配置 + 布线」，永远不会产生数据。

**之后**：保存后约 5 秒内，后端日志开始按间隔出现「日志记录器收到信封」「云发布器把记录发到日志水槽」这样的条目，
持续不断；删图或停服务后立即停止，不留残余任务。

- 用户动作：导入 JSON → 保存 → 看后端日志（前端本轮不加运行态展示）。
- 会看到：`Received WireEnvelope from TimerFast` / `Publishing 1 wire record(s) to log sink` 等周期性日志。
- 边界处理：单个组件抛异常只写日志、不中断调度与其它链路；改组件配置（如间隔）保存后立即生效；删图/关闭应用取消全部调度。
- **不包含**：前端实时数据可视化/端口闪烁、指标面板、持久化运行统计 —— 属于另一个任务。

## 1. 问题

1. `kura-edge-core` 里没有任何调度实现：`Timer` 暴露 `tick()` / `getPeriodMillis()` / `getInitialDelayMillis()` /
   `nextCronFireTime()`，`Fifo` 暴露 `drain()`，但全仓库无人调用（core 中检索 `getPeriodMillis|tick(|drain(` 无命中）。
2. `Timer`/`Fifo` 的 javadoc 写的是「由引擎注入的调度器驱动」——**契约已声明、实现缺失**。
3. 附带问题：引擎 `apply()` 对「保留组件」只调 `configurationService.updateConfiguration(...)`，
   既不重建实例也不回调 `updated()`，所以改了 `simple.interval` 保存后运行期不生效。演示「能跑」会立刻暴露这个洞。

## 2. 备选方案

### 方案 A（推荐）：引擎持有调度器 + api 新增两个 SPI 接口

**api 新增**（`kura-edge-api`，包 `com.kura.wires.engine`，与 `WireEmitContext` 同包）：

```java
/** 自驱动发射组件：由引擎按固定速率或 CRON 表达式调度 tick()。 */
public interface ScheduledEmitter {
    boolean isPeriodic();                 // true = SIMPLE 固定速率
    long initialDelayMillis();            // periodic 专用
    long periodMillis();                  // periodic 专用，> 0
    Instant nextFireTime(Instant after);  // cron 专用
    void tick();
}

/** 需要在引擎执行器上异步 drain 的缓冲型接收组件（Fifo）。 */
public interface DrainableComponent {
    void drain();
}
```

`Timer` 直接 `implements ScheduledEmitter`（现有 `isSimpleType/getInitialDelayMillis/getPeriodMillis/nextCronFireTime/tick`
一一对应，零逻辑重复）；`Fifo` 直接 `implements DrainableComponent`（`drain()` 已存在）。

**core 新增**（`com.kura.wires.core.engine`）：

- 包内接口 `TickScheduler`：`schedulePeriodic(pid, task, initialDelay, period)` / `scheduleOnce(pid, task, delay)` / `cancel(pid)` / `cancelAll()` / `shutdown()`。
  抽象出来是为了**单测可确定性触发**（假实现直接 run 任务，不用 sleep、不会 flaky）。
- 生产实现 `ScheduledExecutorTickScheduler`：`Executors.newSingleThreadScheduledExecutor`，守护线程，命名 `wire-graph-scheduler`；
  每个任务包 `try/catch(Throwable)` + 错误日志（`scheduleAtFixedRate` 一旦抛异常会静默停止后续执行，必须兜住）。
- `WireGraphEngine`：
  - 构造器新增可选参数 `TickScheduler`（默认新建；现有包私有构造器保留，供测试注入）。
  - 注册表监听器里挂/摘调度：`onInstanceCreated` → 若 `instance instanceof ScheduledEmitter` 则按模式排程；
    `onInstanceDestroyed` → `cancel(pid)`。
  - `delete()` / `shutdown()` → `cancelAll()` + `shutdown()`。
  - CRON 模式：一次一触发，tick 后按 `nextFireTime(now)` 重排（`ScheduledExecutorService` 原生不支持表达式）。
  - 保留组件若属性发生变化（与旧图比较）→ 销毁 + 重建实例，从而实现「改间隔生效」（顺带补 `updated()` 不存在的缺口）。
- `EnvelopeRouter.deliver(...)` 成功后：若接收端 `instanceof DrainableComponent`，在同一执行器上异步 `drain()`
  （引擎持有执行器，组件不自建线程池 → 符合 Rules 08 §5；`Fifo.drain()` 内部已 `synchronized` 摘队，并发安全）。

### 方案 B：组件各自建线程/定时器

`Timer` 内部起 `ScheduledExecutorService`，`Fifo` 内部起消费线程。**否决**：直接违反 Rules 08 §5「禁止组件自建线程池」，
线程数随组件数线性增长，生命周期与引擎脱钩，测试无法确定性控制。

### 方案 C：引擎周期性轮询（每 N ms 扫一遍 tick/drain）

**否决**：Timer 的固定速率语义（`scheduleAtFixedRate`）丢失，延迟与 CPU 空转随组件数放大，且无法表达 CRON。

## 3. 选择与原因

选 **A**：唯一同时满足「组件不自建线程池」「生命周期由引擎掌管」「可确定性单测」「能表达 SIMPLE 定速与 CRON」的方案；
新增的 api 面很小（两个接口、共 6 个方法），且与既有组件方法一一对应，不需要改组件内部逻辑。

## 4. Identity & PII

**N/A** —— 本改动不引入任何用户标识、PII 字段或外部数据源，只在内存中传递 `WireEnvelope`（时间戳 + 记录），
调度任务不落盘、不写日志中的业务数据（仅组件自身的既有日志：pid 与属性值，均为配置与技术数据）。

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 新增线程影响线程模型 | 单线程、daemon、固定命名；`shutdown()` 统一关闭；已记录在案并加测试 |
| 一次 tick 异常掐断定速调度 | 每个任务 `try/catch(Throwable)` + 错误日志；专门测试「抛异常后仍继续触发」 |
| 保留组件重建破坏既有行为 | 仅在**属性变化**时重建；core 全量回归 `mvn -pl kura-edge-core verify` |
| 客户端关闭/删图后残留任务 | `cancelAll()` + `shutdown()`，测试用假 scheduler 断言 cancel 次数 |
| 覆盖率门槛（core LINE ≥ 0.80） | 新增类全部配单测；假调度器覆盖 SIMPLE/CRON/取消/异常四条路径 |
| checkstyle 在 validate 阶段卡构建 | 提交前跑 `mvn clean verify`（含 checkstyle、JaCoCo、ArchUnit） |

## 6. 验证计划

**单测（core，核心）**
- 创建实现 `ScheduledEmitter` 的桩实例 → 断言按 `initialDelay/period` 排程；CRON 桩 → 断言单次排程 + tick 后重排。
- 销毁实例 / 删图 / `shutdown()` → 断言 `cancel`/`cancelAll` 被调用。
- tick 抛异常 → 断言不影响后续触发（假调度器手动驱动两次）。
- 接收端为 `DrainableComponent` → 断言投递后触发 `drain()`；非 drainable 不触发。
- 保留组件属性变化 → 断言实例被销毁重建；属性未变 → 不重建。

**单测（components）**
- `Timer` 实现 `ScheduledEmitter` 后各方法委托给 `TimerOptions` 的语义不变（SIMPLE/CRON 两侧、非法类型抛 `IllegalStateException`）。
- `Fifo.drain()` 顺序与幂等（空队列不发射）。

**手测（端到端）**
1. 起后端 + 前端 → 导入 `examples/demo-wires-snapshot.json` → 保存。
2. 后端日志在 10 秒内出现 `Received WireEnvelope from …` 与 `Publishing 1 wire record(s) to log sink`。
3. 改定时器间隔为 1 秒并保存 → 日志频率随之变化（验证热更新）。
4. 删除整图 → 日志立即停止（无残留调度）。

**回归**
- 后端 `mvn clean verify`（checkstyle / JaCoCo / ArchUnit 全绿）。
- 前端 `npm run lint && npm run test:unit && npm run build`（示例冒烟测试同步更新）。

## 7. 分块实现计划（批准后执行）

1. **块 1（api + components）**：新增 `ScheduledEmitter` / `DrainableComponent`；`Timer`、`Fifo` 实现之；组件侧单测。
2. **块 2（core 引擎）**：`TickScheduler` + 生产实现 + 引擎挂载/取消/热更新重建 + `EnvelopeRouter` 的 drain 触发；core 单测。
3. **块 3（示例与文档）**：示例改为可运行拓扑、更新 `examples/README.md` 与主 README、端到端手测记录。
   每块独立 `mvn -pl <module> verify` 通过后再进下一块。

## 8. 待用户决策

1. **示例拓扑**：改成「两条都由 Timer 驱动」（`TimerTick 30s → 过滤器 → 日志`、`TimerFast 5s → FIFO → 云发布器`，
   整张图都能产生日志）还是保留现有云入站链路（那条永远无源、跑不起来）？
   → 倾向改：演示的目的就是「跑起来」，云组件留在 `examples/README.md` 里作为接入说明。
2. **保留组件热更新**：属性变化时重建实例（倾向）还是维持现状（改配置不生效）？
3. 演示间隔保持 30s / 5s，还是改成 5s / 1s 便于观察？

---

## Progress（2026-10-17，方案 A 已批准并实施）

- 状态：**已实现，待最终验收**（块 1/2/3 全部完成；后端 `mvn clean verify`、前端 lint/test/build 均已跑）。
- 相对本 RFC 的偏差一处：`ScheduledEmitter` 的方法名沿用 `Timer` 既有访问器
  （`getInitialDelayMillis()` / `getPeriodMillis()` / `nextCronFireTime(Instant)`），避免同一类里出现两套等价方法。

| 块 | 内容 | 结果 |
|---|---|---|
| 1 | `kura-edge-api` 新增 `ScheduledEmitter` / `DrainableComponent`；`Timer`、`Fifo` 实现（新增 `isPeriodic()`，其余方法加 `@Override`）；组件测试补 3 个场景 | components 71 tests 全绿 |
| 2 | `TickScheduler`（包内 SPI）+ `ScheduledExecutorTickScheduler`（单线程守护、任务异常隔离）；引擎在实例创建/销毁时挂摘排程，`delete()`/`shutdown()` 取消全部；`EnvelopeRouter` 投递后异步 `drain()`；保留组件运行期属性变化则重建实例 | core 96 tests 全绿，覆盖率达标 |
| 3 | 示例改为两条定时器驱动链路、`examples/README.md` 与主 README 3.11/4.1 更新、冒烟测试同步 | 端到端手测通过（见下） |

**实施中顺带修掉的两个真实缺陷**（都在「让图跑起来」的必经路径上，属本 RFC 范围内的必要修复）：

1. **组件实例拿不到自己的配置**：`apply()` 把图 JSON 的渲染属性当成实例属性传给 `registry.createInstance`，
   组件读到的 `type` / `simple.interval` / `regex.filter` 全为空（Timer 退化成空 CRON 表达式、RegexFilter 退化成透传）。
   现改为 `instanceProperties()` = 图属性（含端口数）+ 组件配置属性 + `service.factoryPid` + `wire.*.port.count`。
2. **首次保存后路由表为空**：`apply()` 更新图缓存后没有 `refreshRoutes()`，而监听器回调发生在缓存更新之前，
   出边表停留在旧图（空），投递永远找不到接收端。现于 `apply()` 与 `delete()` 末尾补 `refreshRoutes()`。

**手测记录**（后端日志为证据）：

- 导入 + 保存示例后：出现 `Scheduled wire component TimerFast/TimerTick for periodic ticks: true`；
  上排每 1 秒 `Publishing 1 wire record(s) to log sink`（17 次 / 17 秒）；下排每 5 秒
  `Received WireEnvelope from TimerKeyFilter` + `TIMER : <毫秒>`。
- 热更新：把 `TimerTick.simple.interval` 由 5 改为 2 → 日志出现 `Destroyed`/`Created wire component instance TimerTick`
  并重新 `Scheduled`，随后 tick 间隔变为 2 秒（16:25:57/59/01/03/05），落库值同步为 2。
- 删除整图：`Wire graph deleted` 之后发布计数不再增长（52 → 52，随后 6 秒无新日志）。
