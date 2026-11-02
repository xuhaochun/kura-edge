/**
 * 类型定义：分三层，禁止混用。
 *
 * 1. 线格式（快照 JSON / REST 载荷）：`WireGraphSnapshot`、`GraphComponent`、`GraphWire`、
 *    `ComponentConfigurationDto`、`PropertyDto`、`Ad`、`WireComponentDefinition` ——
 *    字段名与后端/上游 Kura 严格对齐，是兼容契约，禁止改名（rules/05 §9）。
 *    图 JSON 只有 `components` / `wires` 两个顶层键（对照上游
 *    WireGraphJsonMarshallUnmarshallImpl）；组件配置属性不在图 JSON 里，而在配置列表的独立条目中。
 * 2. 视图模型：`WireComponentState`、`ComponentDescriptor`、`ConfigParameter` ——
 *    供画布与表单使用，由 api/ 层的编解码器在线格式之间转换。
 * 3. 统一响应体 `ApiEnvelope`（rules/07）。
 */

/** 统一响应体 {code, message, data}，code=0 成功（rules/07） */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

/** metatype 标量类型（对照上游 GwtConfigParameterType / Scalar） */
export type ScalarType =
  'STRING' | 'LONG' | 'DOUBLE' | 'FLOAT' | 'INTEGER' | 'BOOLEAN' | 'CHAR' | 'BYTE' | 'PASSWORD'

/** 组件配置属性的线格式（对照上游 PropertyDTO）：type + value 包装 */
export interface PropertyDto {
  type: ScalarType
  value: unknown
}

/**
 * 组件实例配置的线格式（对照上游 ComponentConfigurationDTO）：
 * `/graph/snapshot` 与 `/configs` 的 data 项。properties 为 type/value 包装表。
 */
export interface ComponentConfigurationDto {
  pid: string
  properties: Record<string, PropertyDto>
}

/** 属性可选值（对照上游 metatype Option / AdOption） */
export interface AdOption {
  label: string
  value: string
}

/** 配置属性定义（对照上游 metatype AD）：GET /metadata 的 componentOcd 项 */
export interface Ad {
  id: string
  name: string
  description?: string | null
  type: ScalarType
  required: boolean
  defaultValue?: unknown
  min?: number | null
  max?: number | null
  options?: AdOption[] | null
}

/**
 * 组件定义（对照上游 WireComponentDefinitionDTO）。
 * 端口上下限在本项目取工厂默认值；标签取 factoryPid 末段。
 */
export interface WireComponentDefinition {
  factoryPid: string
  minInputPorts: number
  maxInputPorts: number
  defaultInputPorts: number
  minOutputPorts: number
  maxOutputPorts: number
  defaultOutputPorts: number
  inputPortNames?: Record<string, string> | null
  outputPortNames?: Record<string, string> | null
  componentOcd?: Ad[] | null
}

/** 驱动通道描述符（GET /metadata 的 driverChannelDescriptors 项） */
export interface WireGraphDriverDescriptor {
  pid: string
  factoryPid: string
  channelDescriptor: Ad[]
}

/** 驱动 OCD 条目（GET /metadata 的 driverOCDs 项：pid = 驱动工厂 PID） */
export interface WireGraphDriverOcd {
  pid: string
  definition?: { id?: string; name?: string; description?: string; ads?: Ad[] | null } | null
}

/** GET /metadata 返回的元数据（对照上游 WireGraphMetadata；空列表会被省略 = undefined） */
export interface WiresMetadata {
  wireComponentDefinitions?: WireComponentDefinition[] | null
  driverOCDs?: WireGraphDriverOcd[] | null
  driverChannelDescriptors?: WireGraphDriverDescriptor[] | null
  assetChannelDescriptor?: Ad[] | null
}

/** PID 集合（对照上游 PidSetDTO） */
export interface PidSetDto {
  pids: string[]
}

/** PID + factoryPid 对（对照上游 PidAndFactoryPidDTO） */
export interface PidAndFactoryPidDto {
  pid: string
  factoryPid: string
  /** 资产条目：绑定的驱动 PID（驱动条目为空） */
  driverPid?: string | null
  /** 资产条目：资产描述（驱动条目为空） */
  description?: string | null
}

/**
 * 连线：画布内部连线模型（视图模型）。多端口模型 (emitterPid, emitterPort) -> (receiverPid, receiverPort)；
 * 线格式是 {@link GraphWire}（emitter / emitterPort / receiver / receiverPort），
 * 二者在 api/wireGraphCodec 里互相转换。
 */
export interface WireConfiguration {
  emitterPid: string
  receiverPid: string
  emitterPort: number
  receiverPort: number
}

/** 图 JSON 组件项的渲染属性（对照上游 renderingProperties） */
export interface GraphComponentPosition {
  x: number
  y: number
}

/** 端口名以端口编号为键：{"0": "in"}（域图属性里是 inputPortNames.0 / outputPortNames.0） */
export interface GraphComponentRenderingProperties {
  position?: GraphComponentPosition
  inputPortNames?: Record<string, string>
  outputPortNames?: Record<string, string>
}

/**
 * 图 JSON 的组件项（对照上游 marshalComponentProperties）：
 * pid / inputPortCount / outputPortCount 同级，坐标与端口名在 renderingProperties 下。
 * 组件配置属性（含 service.factoryPid）**不在此处**，而在快照配置列表的独立条目里。
 */
export interface GraphComponent {
  pid: string
  inputPortCount?: number
  outputPortCount?: number
  renderingProperties?: GraphComponentRenderingProperties
}

/** 图 JSON 的连线项（线格式，对照上游 marshalWireConfiguration） */
export interface GraphWire {
  emitter: string
  emitterPort: number
  receiver: string
  receiverPort: number
}

/**
 * 图快照（=`WireGraph` 字符串属性的内容）：顶层键只有 `components` / `wires`。
 * 字段名与上游一致，是兼容契约，禁止改名（rules/05 §9）。
 */
export interface WireGraphSnapshot {
  components: GraphComponent[]
  wires: GraphWire[]
}

/* ------------------------------------------------------------------ *
 * 视图模型（画布 / 表单用），由 api 层编解码器转换
 * ------------------------------------------------------------------ */

/** 组件配置字段（视图模型，由 {@link Ad} 转换而来；表单值一律以字符串承载） */
export interface ConfigParameter {
  id: string
  name: string
  description?: string | null
  type: ScalarType
  required: boolean
  defaultValue?: string | null
  cardinality: number
  options?: Record<string, string> | null
  min?: string | null
  max?: string | null
}

/** 组件描述符（视图模型，来自 GET /metadata 的组件定义） */
export interface ComponentDescriptor {
  factoryPid: string
  name: string
  minInputPorts: number
  maxInputPorts: number
  defaultInputPorts: number
  minOutputPorts: number
  maxOutputPorts: number
  defaultOutputPorts: number
  inputPortNames?: Record<string, string> | null
  outputPortNames?: Record<string, string> | null
  properties: ConfigParameter[]
}

/**
 * 画布上的组件状态（视图模型）：位置对应图 JSON 的 renderingProperties.position.{x,y}；
 * `properties` 是组件配置属性（不含 service.factoryPid，factoryPid 单独存放）。
 */
export interface WireComponentState {
  pid: string
  factoryPid: string
  inputPortCount: number
  outputPortCount: number
  position: { x: number; y: number }
  properties: Record<string, unknown>
}
