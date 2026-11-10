---
name: wires-composer-vue
description: Wires 图编辑器 Vue 实现指南：对照 wires_composer.js 与 GWT WiresPanelUi 重建前端画布。
---

# Wires Composer (Vue) Skill

## When to Use

- 在 `frontend/` 实现或修改 Wires 图编辑器（画布、组件面板、配置区、快照导入导出）时
- 需要确认旧前端某个交互/功能是否存在、其行为细节时
- 不用于：后端接口实现（见 wires-rest-api）

## 上游参考（只读）

- 画布引擎（原生 JS，696 行）：
  `/home/idea/IdeaProjects/kura-management-ui/org.eclipse.kura.web2/src/main/resources/org/eclipse/kura/web/js/wires_composer.js`
  —— API：addWireComponent / deleteWireComponent / addWire / clear / getSelectedWireComponent /
  fitContent / zoomIn / zoomOut / checkForCycleExistence / hasCycles / clampToGrid / scale /
  enableBlinking / disableBlinking / setListener（事件：wireCreated/Changed/Deleted、
  wireComponentCreated/Changed/Deleted/Selected/Deselected，且每次变更置 isDirty=true）
  另有 `wires_blink.js`（数据到达时端口闪烁）
- GWT 页面编排：`.../web/client/ui/wires/`
  —— `WiresPanelUi`（工具栏 + 组件面板 + 画布 + 配置区布局）、`WireComponentsAnchorListItem`（面板项）、
  `ConfigurationAreaUi` + `ValidationData`/`ValidationInputCell`（组件配置表单与字段校验）、
  `WiregraphDownloadModal`（快照导出）、`WiresRPC`（后端调用层）
- 组件渲染属性：`composer/WireComponentRenderingProperties`（position 等）

## 功能基线（验收清单）

能力清单以 `.dsh/rules/08-additional-rules.md` §7 为准（验收标准，不是可选项）。
本层只补 §7 没写死的实现口径：节点按**端口**渲染锚点（一个节点多个 handle）、删除组件联动删线；
配置区字段校验对照 `ValidationData`（类型/必填/范围）；导出走 JSON 文件下载；
`isDirty` 收在 Pinia `wiresGraph` store 里，不散落到各组件。

## Procedure（实现一个画布能力）

1. 在 wires_composer.js 找到对应原型方法，读懂几何/事件语义（SVG 坐标系：`clientToLocal`、`transform`）。
2. 选实现载体：优先 Vue Flow 的等价能力（nodes/edges/handles、`useVueFlow`）；
   Vue Flow 不具备的（网格吸附细节、端口闪烁）自研补齐。
3. 状态全部进 Pinia `wiresGraph` store（组件列表、连线列表、dirty 标记、选中项）；
   组件只订阅，不私存图状态。
4. 拓扑/端口校验逻辑进 `composables/useGraphValidation.ts`（纯函数，可单测），
   与后端 `WireGraphEngine` 校验规则一致。
5. API 调用走 `src/api/wireGraph.ts`；类型定义对齐 `src/types/`（字段名与后端快照 JSON 一致）。
6. 写 Vitest：校验函数纯单测 + 关键交互组件测试；`npm run test:unit` 通过。
7. 完成标准：功能基线中对应项可演示，`npm run lint && npm run test:unit && npm run build` 全绿。

## Pitfalls

- GWT 类只是 UI 壳，真正的画布行为在 wires_composer.js —— 找行为先看 JS，不要从 GWT 反推。
- 上游建线时组件端口是**多端口**（一个节点多个输入/输出锚点），Vue Flow 要为每个 port 建 handle，
  连线必须带 port 编号（emitterPort/receiverPort），不能只连节点。
- 图 JSON 字段名是兼容契约：前端 types 必须与后端/上游一致（`components` / `wires` / `renderingProperties.position.{x,y}`）；
  组件配置属性来自 `/graph/snapshot` 的独立配置条目（按 pid 配对），不在图 JSON 里。
- i18n：组件面板、表单标签、错误提示全部走 vue-i18n；上游文案在 web2 的 properties 资源里可对照翻译。
- 解析图 JSON 时**不要把缺失的 inputPortCount/outputPortCount 补成 0**：缺省要留给调用方按组件定义
  的默认端口数回落（`graphPortCount(component, key, descriptor.defaultXxxPorts)`），解析层填 0 会让
  导入 Kura 原生快照时端口数全变 0。
- 顶层键容忍大小写（上游 equalsIgnoreCase），但不要把这条宽容度扩散到深层键：真实 Kura 快照全部小写。


## Verification

- 手动链路：新建两个组件 → 建线 → 故意连成环被前端拦截 → 保存快照 → 刷新页面加载一致 → 删除整图。
- `npm run lint && npm run test:unit && npm run build` 全绿。
