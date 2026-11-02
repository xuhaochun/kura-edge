/**
 * 图 JSON ⇄ REST 快照配置列表的编解码（纯函数，可单测）。
 *
 * 上游线格式（对照 kura 的 WireGraphJsonMarshallUnmarshallImpl）：图整体是 WireGraphService
 * （pid = org.eclipse.kura.wire.graph.WireGraphService）配置里名为 `WireGraph` 的 STRING 属性，
 * 其内容是顶层键只有 `components` / `wires` 的 JSON：
 * - components[] = { pid, inputPortCount, outputPortCount,
 *                    renderingProperties: { position: {x, y}, inputPortNames: {"0": "in"}, outputPortNames: {...} } }
 *   （域图属性里对应 position.x / position.y / inputPortNames.0 / outputPortNames.0）；
 * - wires[]      = { emitter, emitterPort, receiver, receiverPort }。
 *
 * 组件配置属性（含 service.factoryPid、密码占位符）**不在图 JSON 里**，而在 `/graph/snapshot`
 * 配置列表各自的独立条目中；因此读取返回「图 JSON + 其余条目摊平后的属性表」两份数据，
 * 写入时也要分别还原回配置列表。
 *
 * 解析宽容度对照上游：components 必须是数组；组件项缺 pid、连线缺 emitter/receiver 的条目被跳过；
 * 缺 inputPortCount / outputPortCount / renderingProperties / position 时字段保持缺失，
 * 由调用方用 {@link graphPortCount} / {@link graphPosition} 的 fallback 应用缺省值
 * （store 的 fallback 是组件定义里的默认端口数 / 原点）。
 */
import type {
  ComponentConfigurationDto,
  ComponentDescriptor,
  GraphComponent,
  GraphComponentPosition,
  GraphComponentRenderingProperties,
  GraphWire,
  PropertyDto,
  ScalarType,
  WireComponentState,
  WireGraphSnapshot,
} from '@/types/wires'

/** 图服务配置的 PID（对照 WireGraphConstants.WIRE_GRAPH_SERVICE_PID） */
export const WIRE_GRAPH_SERVICE_PID = 'org.eclipse.kura.wire.graph.WireGraphService'

/** 图 JSON 所在的字符串属性名（对照 WireGraphConstants.WIRE_GRAPH_PROPERTY） */
export const WIRE_GRAPH_PROPERTY = 'WireGraph'

/** 组件配置中的工厂 PID 属性名（对照 WireGraphConstants.SERVICE_FACTORY_PID） */
export const SERVICE_FACTORY_PID = 'service.factoryPid'

/** 图 JSON 组件项：输入端口数（与 pid 同级，对照上游 INPUT_PORT_COUNT_KEY） */
export const INPUT_PORT_COUNT = 'inputPortCount'

/** 图 JSON 组件项：输出端口数（与 pid 同级，对照上游 OUTPUT_PORT_COUNT_KEY） */
export const OUTPUT_PORT_COUNT = 'outputPortCount'

/** 域图属性：画布 X 坐标（上游图属性名 position.x，JSON 里位于 renderingProperties.position.x） */
export const POSITION_X = 'position.x'

/** 域图属性：画布 Y 坐标（上游图属性名 position.y，JSON 里位于 renderingProperties.position.y） */
export const POSITION_Y = 'position.y'

/** 密码占位符，与后端 ConfigDtoMapper.PASSWORD_PLACEHOLDER 一致 */
export const PASSWORD_PLACEHOLDER = 'PASSWORD'

/** 组件工厂 PID 缺失时的错误 key（UI 层翻译） */
export const FACTORY_PID_MISSING = 'wires.validation.factoryPidMissing'

/** 端口数所在字段名 */
export type PortCountKey = typeof INPUT_PORT_COUNT | typeof OUTPUT_PORT_COUNT

/** 配置列表解析结果：图拓扑 + 各组件配置属性（已摊平 {type,value} 包装） */
export interface ExtractedGraphSnapshot {
  graph: WireGraphSnapshot
  configurationProperties: Record<string, Record<string, unknown>>
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function toNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * 取顶层字段：上游解析对 `components` / `wires` 用 equalsIgnoreCase
 * （WireGraphJsonMarshallUnmarshallImpl#unmarshalToWireGraphConfiguration），
 * 这里保持同样的宽容度，精确匹配优先。
 */
function topLevelField(value: Record<string, unknown>, key: string): unknown {
  if (key in value) {
    return value[key]
  }
  const matched = Object.keys(value).find(
    (candidate) => candidate.toLowerCase() === key.toLowerCase(),
  )
  return matched === undefined ? undefined : value[matched]
}

/** 端口名对象（renderingProperties.inputPortNames / outputPortNames）原样保留字符串值 */
function normalizePortNames(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) {
    return undefined
  }
  const names: Record<string, string> = {}
  for (const [port, name] of Object.entries(value)) {
    if (typeof name === 'string') {
      names[port] = name
    }
  }
  return Object.keys(names).length > 0 ? names : undefined
}

/** 规范化组件项的 renderingProperties：缺省字段不被补齐（由调用方用 fallback 应用缺省） */
function normalizeRenderingProperties(
  value: unknown,
): GraphComponentRenderingProperties | undefined {
  if (!isRecord(value)) {
    return undefined
  }
  const rendering: GraphComponentRenderingProperties = {}
  if (isRecord(value.position)) {
    rendering.position = {
      x: toNumber(value.position.x, 0),
      y: toNumber(value.position.y, 0),
    }
  }
  const inputPortNames = normalizePortNames(value.inputPortNames)
  if (inputPortNames) {
    rendering.inputPortNames = inputPortNames
  }
  const outputPortNames = normalizePortNames(value.outputPortNames)
  if (outputPortNames) {
    rendering.outputPortNames = outputPortNames
  }
  return rendering
}

function normalizeGraphComponent(raw: Record<string, unknown>): GraphComponent {
  const component: GraphComponent = { pid: raw.pid as string }
  if (typeof raw[INPUT_PORT_COUNT] === 'number' && Number.isFinite(raw[INPUT_PORT_COUNT])) {
    component.inputPortCount = raw[INPUT_PORT_COUNT] as number
  }
  if (typeof raw[OUTPUT_PORT_COUNT] === 'number' && Number.isFinite(raw[OUTPUT_PORT_COUNT])) {
    component.outputPortCount = raw[OUTPUT_PORT_COUNT] as number
  }
  const renderingProperties = normalizeRenderingProperties(raw.renderingProperties)
  if (renderingProperties) {
    component.renderingProperties = renderingProperties
  }
  return component
}

/** 规范化连线项：缺 emitter/receiver 的条目返回 null（对照上游跳过该条） */
function normalizeGraphWire(raw: unknown): GraphWire | null {
  if (!isRecord(raw) || typeof raw.emitter !== 'string' || typeof raw.receiver !== 'string') {
    return null
  }
  return {
    emitter: raw.emitter,
    emitterPort: toNumber(raw.emitterPort, 0),
    receiver: raw.receiver,
    receiverPort: toNumber(raw.receiverPort, 0),
  }
}

/**
 * 校验并规范化图 JSON（即 `WireGraph` 属性的内容）。
 *
 * @param value 待校验的 JSON 值
 * @returns 规范化图快照；顶层不是对象、`components` 不是数组或 `wires` 不是数组时返回 null
 */
export function parseGraphSnapshot(value: unknown): WireGraphSnapshot | null {
  if (!isRecord(value)) {
    return null
  }
  const rawComponents = topLevelField(value, 'components')
  if (!Array.isArray(rawComponents)) {
    return null
  }
  const rawWires = topLevelField(value, 'wires')
  if (rawWires !== undefined && !Array.isArray(rawWires)) {
    return null
  }

  const components: GraphComponent[] = []
  for (const raw of rawComponents) {
    if (!isRecord(raw) || typeof raw.pid !== 'string') {
      continue
    }
    components.push(normalizeGraphComponent(raw))
  }

  const wires: GraphWire[] = []
  for (const raw of rawWires ?? []) {
    const wire = normalizeGraphWire(raw)
    if (wire) {
      wires.push(wire)
    }
  }

  return { components, wires }
}

/** 取图 JSON 组件项的端口数；字段缺失/非法时返回 fallback（缺省由调用方给） */
export function graphPortCount(
  component: GraphComponent,
  key: PortCountKey,
  fallback: number,
): number {
  return toNumber(component[key], fallback)
}

/** 取图 JSON 组件项的位置（renderingProperties.position）；缺失时返回 fallback */
export function graphPosition(
  component: GraphComponent,
  fallback: GraphComponentPosition,
): GraphComponentPosition {
  const position = component.renderingProperties?.position
  if (!position) {
    return { ...fallback }
  }
  return {
    x: toNumber(position.x, fallback.x),
    y: toNumber(position.y, fallback.y),
  }
}

/** 摊平条目 properties 的 {type, value} 包装，只取 value */
function propertyValue(property: unknown): unknown {
  return isRecord(property) ? property.value : undefined
}

/** 除 WireGraphService 条目外的组件配置条目摊平成 {pid: {key: value}}（丢掉 {type,value} 包装） */
/**
 * 配置列表 → `pid -> 属性表`（去掉 {type,value} 包装，跳过图服务自身条目）。
 * 除快照解析外，图 JSON 导入时补回的配置也走这里。
 *
 * @param configs 配置列表（GET /graph/snapshot 或 POST /configs/by-pids 的 data）
 * @returns 每个组件 pid 对应的属性表
 */
export function flattenComponentProperties(
  configs: ComponentConfigurationDto[],
): Record<string, Record<string, unknown>> {
  const result: Record<string, Record<string, unknown>> = {}
  for (const config of configs) {
    if (!isRecord(config) || typeof config.pid !== 'string') {
      continue
    }
    if (config.pid === WIRE_GRAPH_SERVICE_PID) {
      continue
    }
    const source = isRecord(config.properties) ? config.properties : {}
    const properties: Record<string, unknown> = {}
    for (const [key, property] of Object.entries(source)) {
      properties[key] = propertyValue(property)
    }
    result[config.pid] = properties
  }
  return result
}

/**
 * 从 `/graph/snapshot` 的配置列表解析出图拓扑与组件配置属性。
 *
 * @param configs 配置列表
 * @returns 图 JSON + 摊平后的组件属性表；缺 WireGraphService 条目、`WireGraph` 属性类型非
 *          STRING、JSON 非法或图结构非法时返回 null
 */
export function extractGraphSnapshot(
  configs: ComponentConfigurationDto[] | null | undefined,
): ExtractedGraphSnapshot | null {
  const list = configs ?? []
  const entry = list.find((config) => isRecord(config) && config.pid === WIRE_GRAPH_SERVICE_PID)
  const rawProperties = isRecord(entry?.properties) ? entry.properties : null
  const property = rawProperties ? rawProperties[WIRE_GRAPH_PROPERTY] : undefined
  if (!isRecord(property) || property.type !== 'STRING' || typeof property.value !== 'string') {
    return null
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(property.value)
  } catch {
    return null
  }
  const graph = parseGraphSnapshot(parsed)
  if (!graph) {
    return null
  }
  return { graph, configurationProperties: flattenComponentProperties(list) }
}

/**
 * 解析导入的 JSON 文件内容：既能吃图 JSON 本身（components/wires），
 * 也能吃 `{configs: [...]}` 形态的完整快照列表。
 *
 * @param value 已 JSON.parse 的文件内容
 * @returns 图 JSON + 组件属性表；非法输入返回 null
 */
export function parseSnapshotFile(value: unknown): ExtractedGraphSnapshot | null {
  if (isRecord(value) && Array.isArray(value.configs)) {
    return extractGraphSnapshot(value.configs as ComponentConfigurationDto[])
  }
  const graph = parseGraphSnapshot(value)
  return graph ? { graph, configurationProperties: {} } : null
}

/**
 * 按组件定义推断属性类型：优先取 OCD 声明的类型（PASSWORD 必须来自此处，
 * 否则密码占位符无法被后端还原），未声明时按取值推断。
 */
function scalarTypeOf(
  factoryPid: string,
  key: string,
  value: unknown,
  descriptors: Record<string, ComponentDescriptor>,
): ScalarType {
  const parameter = descriptors[factoryPid]?.properties.find((candidate) => candidate.id === key)
  if (parameter) {
    return parameter.type
  }
  if (typeof value === 'boolean') {
    return 'BOOLEAN'
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'INTEGER' : 'DOUBLE'
  }
  return 'STRING'
}

/** 组件配置 → 配置列表条目：属性走 {type, value} 包装并补上 service.factoryPid */
function toComponentConfiguration(
  component: WireComponentState,
  descriptors: Record<string, ComponentDescriptor>,
): ComponentConfigurationDto {
  if (component.factoryPid === '') {
    throw new Error(FACTORY_PID_MISSING)
  }
  const properties: Record<string, PropertyDto> = {}
  for (const [key, value] of Object.entries(component.properties)) {
    properties[key] = { type: scalarTypeOf(component.factoryPid, key, value, descriptors), value }
  }
  properties[SERVICE_FACTORY_PID] = { type: 'STRING', value: component.factoryPid }
  return { pid: component.pid, properties }
}

/**
 * 把图 JSON + 组件状态装配为 `PUT /graph/snapshot` 的配置列表：
 * 第一条是 WireGraphService 配置（`WireGraph` STRING 属性 = 图 JSON），随后每个组件一条
 * （组件配置属性来自 store 状态，不来自图 JSON）。
 *
 * @param graph 图 JSON（组件拓扑/端口/位置）
 * @param components 画布组件状态（携带组件配置属性与 factoryPid）
 * @param descriptors 组件定义（用于给属性推断类型）
 * @returns 配置列表
 * @throws Error 组件缺少 factoryPid 时（message 为 i18n key）
 */
export function toSnapshotConfigurations(
  graph: WireGraphSnapshot,
  components: WireComponentState[],
  descriptors: Record<string, ComponentDescriptor> = {},
): ComponentConfigurationDto[] {
  const graphService: ComponentConfigurationDto = {
    pid: WIRE_GRAPH_SERVICE_PID,
    properties: {
      [WIRE_GRAPH_PROPERTY]: { type: 'STRING', value: JSON.stringify(graph) },
    },
  }
  const componentConfigs = components.map((component) =>
    toComponentConfiguration(component, descriptors),
  )
  return [graphService, ...componentConfigs]
}
