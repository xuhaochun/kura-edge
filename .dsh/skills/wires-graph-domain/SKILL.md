---
name: wires-graph-domain
description: Wires 领域模型与图引擎拆分指南：对照 kura/kura-wires 源码实现 kura-edge-api 与 kura-edge-core。
---

# Wires Graph 领域模型与图引擎 Skill

## When to Use

- 在 `kura-edge-api` 建领域模型 / SPI，或在 `kura-edge-core` 实现图引擎、校验、快照持久化时
- 需要确认上游某个类/字段的语义、快照 JSON 格式、端口模型时
- 不用于：REST Controller 与 DTO（见 wires-rest-api）、前端编辑器（见 wires-composer-vue）

## 上游参考（只读）

- API 层：`/home/idea/IdeaProjects/kura/kura/org.eclipse.kura.api/src/main/java/org/eclipse/kura/wire/`
  （WireComponent、WireEmitter、WireReceiver、WireEnvelope、WireRecord、WireSupport、WireConfiguration）
  与 `.../org/eclipse/kura/wire/graph/`（WireGraphService、WireGraphConfiguration、WireComponentConfiguration、
  MultiportWireConfiguration、MultiportWireSupport、Port/EmitterPort/ReceiverPort、PortAggregator）
- 引擎实现：`/home/idea/IdeaProjects/kura-wires/bundles/org.eclipse.kura.wire.provider/src/main/java/org/eclipse/kura/internal/wire/`
  （WireGraphServiceImpl.java — 图的 get/update/delete/refresh 与 OSGi WireAdmin 交互；
  WireComponentTrackerCustomizer.java — 组件发现/跟踪）
- 组件实现范例：`kura-wires/bundles/org.eclipse.kura.wire.component.provider/.../internal/wire/`（timer、logger、regexfilter、fifo、publisher、subscriber、asset）

## 核心领域概念（速览）

数据单元 `WireEnvelope`（含时间戳）承载多个 `WireRecord`（key-value，**不可变**）；
组件角色 `WireEmitter` / `WireReceiver` / `WireComponent`；
连线是 `MultiportWireConfiguration(emitterPid, receiverPid, emitterPort, receiverPort)`；
整图 = `WireGraphConfiguration`（组件配置列表 + 连线列表），序列化为 WireGraphService 配置里名为 `WireGraph` 的字符串属性。

**硬约束不在本文件重复**：多端口范围校验、快照 JSON 键名与外壳结构、Record 不可变语义
一律以 `.dsh/rules/08-additional-rules.md` §1–§3 为准（那里是权威版本，改动只改那处）。

## Procedure（移植一个引擎能力）

1. 读上游对应实现（先 `WireGraphServiceImpl` 定位方法），记录行为与边界条件。
2. 写 `kura-edge-api` 中的接口/模型（保留类名与字段名，包名改 `com.kura.wires.*`，去 OSGi 注解）。
3. 在 `kura-edge-core` 实现：OSGi 服务跟踪 → 构造器注入的注册表；ConfigurationAdmin → JPA Repository；WireAdmin → 引擎自管连线。
4. 按 Gherkin 风格写测试（正反路径），跑 `mvn -pl kura-edge-core test`。
5. 环检测、端口校验必须覆盖；快照序列化往返测试必须通过。
6. 完成标准：`mvn clean verify` 通过且该能力有对应测试类。

## Pitfalls

- 上游图更新不是整体替换组件实例：先 diff 新旧图，只重建变化的连线与组件，照抄整体重建会丢运行状态。
- `WireRecord` 的 value 类型受 `Scalar` 约束，移植时保留类型校验，否则上游快照导入后行为不一致。
- 引擎与组件解耦：组件通过注册表发现，禁止 core 直接 import components 里的类。

## Verification

- `mvn -pl kura-edge-core test` 全绿；
- 用一段上游导出的快照 JSON 做加载往返测试（load → save → JSON 字段与输入一致）。
