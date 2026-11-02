/**
 * 资产通道表的纯逻辑（对照上游 AssetModelImpl + LegacyChannelModel）：
 * 通道参数定义（AD 列表）→ 列定义；资产扁平属性表 ⇄ 通道行；通道名校验；新建通道的默认行。
 *
 * 这些函数不碰 Vue 状态与网络，全部可单测（rules/06：composables 语句覆盖率 ≥ 80%）。
 *
 * 兼容契约（RFC 002 §3.1）：
 * - 属性键 = `<channelName>#<paramId>`，`#` 只切第一个；
 * - 基座参数（`+enabled`/`+type`…）在前、驱动参数在后；
 * - 值一律以字符串承载，只有 BOOLEAN 类型序列化成真正的布尔（上游 `emit.*` 只认 Boolean）。
 */
import type { Ad } from '@/types/wires'
import type {
  ChannelColumn,
  ChannelRow,
  ChannelType,
  DataType,
  ScaleOffsetType,
} from '@/types/driversAssets'

/** 通道配置键分隔符（对照上游 CHANNEL_PROPERTY_SEPARATOR） */
export const CHANNEL_SEPARATOR = '#'

/** 通道名禁止字符（对照上游 CHANNEL_NAME_PROHIBITED_CHARS，注意含空格） */
export const PROHIBITED_CHARS = '#/+, '

/** 通道名默认前缀（对照上游 wiresChannel） */
export const CHANNEL_NAME_PREFIX = 'Channel-'

/** 参数 id 以 `+` 开头即基座参数 */
export function isBaseParameterId(id: string): boolean {
  return id.startsWith('+')
}

/** 通道参数定义是否基座参数 */
export function isBaseParameter(ad: Ad): boolean {
  return isBaseParameterId(ad.id)
}

/** 参数展示名：`+value.type` → `value.type`（对照上游取 `+` 之后的部分） */
export function parameterLabel(id: string): string {
  return isBaseParameterId(id) ? id.slice(1) : id
}

/**
 * 通道参数定义 → 列定义（保持后端顺序：基座在前、驱动在后）。
 *
 * @param descriptor 合并后的通道 AD 列表
 * @returns 列定义
 */
export function toChannelColumns(descriptor: Ad[] | null | undefined): ChannelColumn[] {
  return (descriptor ?? []).map((ad) => {
    const options: Record<string, string> = {}
    for (const option of ad.options ?? []) {
      options[option.value] = option.label
    }
    return {
      id: ad.id,
      name: parameterLabel(ad.id),
      description: ad.description ?? null,
      type: ad.type,
      required: ad.required ?? false,
      defaultValue: ad.defaultValue,
      options: Object.keys(options).length > 0 ? options : null,
      base: isBaseParameter(ad),
    }
  })
}

/** 属性值 → 字符串（null/undefined → 空串，布尔 → "true"/"false"） */
export function toCellString(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  return String(value)
}

/**
 * 扁平属性表 → 通道行（按属性出现顺序，`#` 前为通道名）。
 *
 * @param properties 资产配置属性表
 * @returns 通道行
 */
export function parseChannelRows(
  properties: Record<string, unknown> | null | undefined,
): ChannelRow[] {
  const rows = new Map<string, ChannelRow>()
  for (const [key, value] of Object.entries(properties ?? {})) {
    const position = key.indexOf(CHANNEL_SEPARATOR)
    if (position <= 0 || position >= key.length - 1) {
      continue
    }
    const name = key.slice(0, position)
    const paramId = key.slice(position + 1)
    let row = rows.get(name)
    if (!row) {
      row = { name, params: {} }
      rows.set(name, row)
    }
    row.params[paramId] = toCellString(value)
  }
  return [...rows.values()]
}

/**
 * 通道行 → 扁平属性表条目（保留资产级属性，重写通道参数键）。
 *
 * @param properties 原始资产属性表（含 `driver.pid`、`asset.desc` 等）
 * @param rows 通道行
 * @param columns 列定义（决定哪些值序列化为布尔）
 * @returns 新的属性表
 */
export function serializeChannelRows(
  properties: Record<string, unknown>,
  rows: ChannelRow[],
  columns: ChannelColumn[],
): Record<string, unknown> {
  const serialized: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(properties)) {
    if (!key.includes(CHANNEL_SEPARATOR)) {
      serialized[key] = value
    }
  }
  const booleanParams = new Set(
    columns.filter((column) => column.type === 'BOOLEAN').map((column) => column.id),
  )
  for (const row of rows) {
    for (const [paramId, value] of Object.entries(row.params)) {
      if (value === undefined || value === null) {
        continue
      }
      serialized[`${row.name}${CHANNEL_SEPARATOR}${paramId}`] = booleanParams.has(paramId)
        ? value === 'true'
        : value
    }
  }
  return serialized
}

/**
 * 新建通道的默认行：每个列都带默认值（对照上游「克隆每个描述符参数」的做法）。
 *
 * @param name 新通道名
 * @param columns 列定义
 * @returns 通道行
 */
export function createChannelRow(name: string, columns: ChannelColumn[]): ChannelRow {
  const params: Record<string, string> = {}
  for (const column of columns) {
    params[column.id] = toCellString(column.defaultValue)
  }
  params['+name'] = name
  return { name, params }
}

/**
 * 建议的新通道名（`Channel-1`、`Channel-2`… 取第一个没被占用的）。
 *
 * @param rows 现有通道行
 * @returns 建议名
 */
export function suggestChannelName(rows: ChannelRow[]): string {
  const existing = new Set(rows.map((row) => row.name))
  let index = 1
  while (existing.has(`${CHANNEL_NAME_PREFIX}${index}`)) {
    index += 1
  }
  return `${CHANNEL_NAME_PREFIX}${index}`
}

/** 通道名校验结果：空串 = 合法，否则是 i18n 键 */
export type ChannelNameError = '' | 'empty' | 'invalidCharacters' | 'duplicate'

/**
 * 校验通道名（对照上游 validateChannelName 的规则与顺序）。
 *
 * @param name 待校验的名字
 * @param rows 现有通道行（不含正在编辑的新行）
 * @returns 错误键，合法时为空串
 */
export function validateChannelName(name: string, rows: ChannelRow[]): ChannelNameError {
  if (name.trim() === '') {
    return 'empty'
  }
  for (const char of name) {
    if (PROHIBITED_CHARS.includes(char)) {
      return 'invalidCharacters'
    }
  }
  if (rows.some((row) => row.name === name)) {
    return 'duplicate'
  }
  return ''
}

/** 行内某列的取值是否为空（必填列） */
export function isCellMissing(row: ChannelRow, column: ChannelColumn): boolean {
  if (!column.required) {
    return false
  }
  const value = row.params[column.id]
  return value === undefined || value === null || String(value).trim() === ''
}

/**
 * 通道表是否整体可应用：必填列都有值，且方向/取值类型是合法枚举。
 *
 * @param rows 通道行
 * @param columns 列定义
 * @returns 全部合法时为 true
 */
export function isChannelTableValid(rows: ChannelRow[], columns: ChannelColumn[]): boolean {
  const channelTypes: ChannelType[] = ['READ', 'READ_WRITE', 'WRITE']
  const dataTypes: DataType[] = [
    'BOOLEAN',
    'BYTE_ARRAY',
    'DOUBLE',
    'INTEGER',
    'LONG',
    'FLOAT',
    'STRING',
  ]
  const scaleOffsetTypes: ScaleOffsetType[] = [
    'DEFINED_BY_VALUE_TYPE',
    'FLOAT',
    'DOUBLE',
    'INTEGER',
    'LONG',
  ]
  return rows.every((row) => {
    if (columns.some((column) => isCellMissing(row, column))) {
      return false
    }
    const type = row.params['+type'] as ChannelType | undefined
    const valueType = row.params['+value.type'] as DataType | undefined
    const scaleOffsetType = row.params['+scaleoffset.type'] as ScaleOffsetType | undefined
    if (type !== undefined && !channelTypes.includes(type)) {
      return false
    }
    if (valueType !== undefined && !dataTypes.includes(valueType)) {
      return false
    }
    return scaleOffsetType === undefined || scaleOffsetTypes.includes(scaleOffsetType)
  })
}
