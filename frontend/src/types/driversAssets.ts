/**
 * Drivers and Assets 的类型定义（线格式 + 视图模型）。
 *
 * 线格式字段名与后端 DTO / 上游 Kura 对齐（禁止改名，见 rules/05 §9）：
 * - 实例列表 `{ components: [{ pid, factoryPid }] }`（对照上游 PidAndFactoryPidSet）
 * - 资产通道 `{ name, type, valueType, scaleOffsetType, valueScale, valueOffset, unit, enabled }`
 * - 读写记录 `{ name, valueType, value, status, exceptionMessage }`
 * - 资产配置属性表是**扁平 map**（`driver.pid`、`asset.desc`、`<channel>#<param>`），
 *   与 Kura 原生快照的属性键逐字一致。
 */
import type { Ad, PidAndFactoryPidDto } from './wires'

export type { Ad, PidAndFactoryPidDto }

/** 组件面板拖拽载荷的 MIME 类型（组件或已存在的资产实例，载荷是 JSON） */
export const PALETTE_MIME = 'application/x-wires-node'

/** 资产组件工厂 PID（与上游 WireAssetConstants.CONF_PID 一致） */
export const WIRE_ASSET_FACTORY_PID = 'org.eclipse.kura.wire.WireAsset'

/** 通道读写方向（对照上游 ChannelType） */
export type ChannelType = 'READ' | 'READ_WRITE' | 'WRITE'

/** 通道取值类型（对照上游 DataType，枚举顺序是兼容契约） */
export type DataType = 'BOOLEAN' | 'BYTE_ARRAY' | 'DOUBLE' | 'INTEGER' | 'LONG' | 'FLOAT' | 'STRING'

/** scale/offset 运算类型（对照上游 ScaleOffsetType） */
export type ScaleOffsetType = 'DEFINED_BY_VALUE_TYPE' | 'FLOAT' | 'DOUBLE' | 'INTEGER' | 'LONG'

/** 实例列表（对照上游 PidAndFactoryPidSet） */
export interface PidAndFactoryPidSetDto {
  components: PidAndFactoryPidDto[]
}

/** 资产通道（GET /api/v1/assets/{pid} 的 data 项） */
export interface AssetChannelDto {
  name: string
  type: ChannelType
  valueType: DataType
  scaleOffsetType: ScaleOffsetType
  valueScale?: number | null
  valueOffset?: number | null
  unit?: string | null
  enabled: boolean
}

/** 一次读写的返回记录（带状态与失败原因） */
export interface ChannelRecordDto {
  name: string
  valueType?: DataType | null
  value?: unknown
  status?: 'SUCCESS' | 'FAILURE' | null
  exceptionMessage?: string | null
}

/** 写请求的单条记录 */
export interface WriteChannelDto {
  name: string
  type: DataType
  value: unknown
}

/** 驱动通道描述符条目（GET /metadata/drivers/channel-descriptors 的 data 项） */
export interface DriverDescriptorDto {
  pid: string
  factoryPid: string
  channelDescriptor: Ad[]
}

/** 驱动实例（列表页展示用） */
export interface DriverInfo {
  pid: string
  factoryPid: string
}

/** 资产实例（列表页展示用） */
export interface AssetInfo {
  pid: string
  factoryPid: string
  /** 绑定的驱动 PID（从资产配置的 driver.pid 解析） */
  driverPid: string
  description: string
}

/**
 * 通道表的一行（视图模型）：键是通道参数 id（`+enabled`、`simulated.register`…），值一律是字符串。
 * 从资产配置属性表派生，改完再序列化回属性表。
 */
export interface ChannelRow {
  name: string
  params: Record<string, string>
}

/** 通道列定义（表头 = 参数名，顺序 = 基座参数 + 驱动参数） */
export interface ChannelColumn {
  id: string
  name: string
  description?: string | null
  type: Ad['type']
  required: boolean
  defaultValue?: unknown
  options?: Record<string, string> | null
  /** 基座参数（`+` 前缀）还是驱动参数 */
  base: boolean
}

/** 数据页一行（视图模型） */
export interface AssetDataRow {
  name: string
  type: ChannelType
  valueType: DataType
  /** 读到的值（失败时为 null） */
  value: unknown
  /** 用户在输入框里改成的值（字符串） */
  edited: string
  status: 'UNKNOWN' | 'SUCCESS' | 'FAILURE' | 'MODIFIED' | 'DISABLED'
  exceptionMessage?: string | null
}
