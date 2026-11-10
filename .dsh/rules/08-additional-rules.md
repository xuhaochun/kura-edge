# 附加规则：从三个上游项目总结出的领域约束（Rules 08）

以下规则来自对上游源码的阅读总结，是**拆分移植时最容易踩坑**的地方，优先级高于一般风格偏好。

## 1. 多端口模型必须完整保留

上游是**多端口** Wires：连线不是组件对组件，而是 `(emitterPid, emitterPort) → (receiverPid, receiverPort)`；
每个组件配置里有 `inputPortCount` / `outputPortCount`（见 `WireGraphServiceImpl` 的
`OUTPUT_PORT_COUNT` / `INPUT_PORT_COUNT` 常量与 `MultiportWireConfiguration`）。
前端画布上每个节点要渲染多个端口锚点；后端校验 `emitterPort ∈ [0, outputPortCount)`、
`receiverPort ∈ [0, inputPortCount)`。禁止简化为单端口。

## 2. 快照 JSON 与上游保持兼容

- 图结构字段（以 `WireGraphJsonMarshallUnmarshallImpl` 为准，Java 字段名不是 JSON 键名）：
  `components[]`（每项 = `pid` + `inputPortCount` + `outputPortCount` + `renderingProperties{position:{x,y}, inputPortNames, outputPortNames}`）与
  `wires[]`（每项 = `emitter, emitterPort, receiver, receiverPort`）。
  **图 JSON 不含组件配置属性**（`service.factoryPid`、`topic` 等由各组件的配置条目单独保存，读取时回填，对照上游 `WireGraphServiceImpl#get`）。
- 图整体是 WireGraphService（pid=`org.eclipse.kura.wire.graph.WireGraphService`）配置里名为 `WireGraph` 的字符串属性——保存快照接口要继续输出这个外壳结构，才能导入 Kura 原生快照。
- 消息模型：`WireEnvelope`（时间戳 + 属性列表）携带若干 `WireRecord`（key-value），**不可变**；组件不得修改传入的 Record，需要变更时创建新实例。

## 3. 图更新的原子性与顺序

对照 `WireGraphServiceImpl` / `WireRestService.updateWireGraphConfiguration`：
校验（含环检测、端口校验、组件必须被图引用）→ 计算旧图与新图的差异 →
停止删除的组件 → 重建 WireAdmin 等价的连线 → 启动新增组件 → 刷新受影响组件 → 快照落库。
任一子步骤失败用 FailureHandler 聚合，不允许留下"半新半旧"的图。

## 4. 环检测要前后端都有

上游前端 `wires_composer.js#checkForCycleExistence` 只拦 UI 操作；后端引擎必须独立兜底
（REST 可以绕过前端）。两边算法保持一致：对有向图 DFS 检测环，建线/更新图时执行。

## 5. 组件生命周期与线程模型

- 组件实现 `WireEmitter` / `WireReceiver`（或 `WireComponent`），引擎负责实例生命周期，
  对照上游用 OSGi ServiceTracker 跟踪的部分 → `core/registry` 的组件注册表。
- Timer 等主动发射组件在工作线程发 `WireEnvelope`；接收端组件的 `onWireReceive`
  不应长时间阻塞——引擎提供异步执行器，禁止组件自建线程池（对照上游 Fifo/Timer 的 Executor 使用）。

## 6. 配置即数据，密码必须脱敏

- 组件配置（OCD/AD 概念）在数据库中持久化；类型系统对照上游 metatype：`Scalar`（STRING/LONG/DOUBLE/FLOAT/INTEGER/BOOLEAN/CHAR/BYTE/PIN 等）。
- 任何配置读取接口返回前把密码类属性替换为占位符；保存时若收到占位符则保留原值（对照上游 `DTOUtil.toComponentConfigurationList(..., true)` 与占位符逻辑）。

## 7. 前端编辑器功能基线（对照 wires_composer.js + WiresPanelUi）

Vue 版图编辑器必须覆盖旧前端的能力清单：组件面板（按 factoryPid 分组）、拖拽建组件、
端口连线/删线、选中组件出配置区（`ConfigurationAreaUi` + `ValidationData` 字段校验）、
画布缩放/居中/网格吸附、环检测提示、快照导入导出（`WiregraphDownloadModal`）、
未保存变更脏标记（composer 的 `isDirty`）。这是验收标准，不是可选项。

## 8. 逐步替换策略

移植顺序建议（每个阶段可独立验证）：api 领域模型 → core 引擎 + 快照 → rest + 内置组件（Timer/Logger/RegexFilter 优先）→ 前端编辑器 → db/asset/云发布订阅组件。
Asset/Driver 体系（`asset.provider`、`driver.*`）复杂度高，放最后，先以 SPI 占位。

## 9. 许可证合规

上游代码为 EPL-2.0（部分文件含 Eurotech 版权头）。移植文件保留原版权声明并注明修改，
项目对外分发时遵守 EPL-2.0；在 README 中声明基于 Eclipse Kura 派生。
