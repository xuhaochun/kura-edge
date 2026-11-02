/**
 * 图拓扑与配置字段校验（纯函数，可单测）。
 * 环检测算法与上游 wires_composer.js#hasCycles / 后端 WireGraphEngine 保持一致；
 * 字段校验对照上游 ValidationInputCell + GwtConfigComponent.isValid。
 */
import type { ConfigParameter, WireConfiguration } from '@/types/wires'

export interface PidEdge {
  emitterPid: string
  receiverPid: string
}

export interface ParameterValidationResult {
  valid: boolean
  /** i18n key（wires.validation.*），无效时给出 */
  messageKey?: string
  params?: Record<string, unknown>
}

/** 有向图 DFS 环检测：存在环返回 true */
export function hasCycle(edges: PidEdge[]): boolean {
  const adjacency = new Map<string, string[]>()
  const nodes = new Set<string>()
  for (const edge of edges) {
    nodes.add(edge.emitterPid)
    nodes.add(edge.receiverPid)
    const targets = adjacency.get(edge.emitterPid) ?? []
    targets.push(edge.receiverPid)
    adjacency.set(edge.emitterPid, targets)
  }

  const visiting = new Set<string>()
  const done = new Set<string>()
  const walk = (node: string): boolean => {
    if (visiting.has(node)) {
      return true
    }
    if (done.has(node)) {
      return false
    }
    visiting.add(node)
    for (const next of adjacency.get(node) ?? []) {
      if (walk(next)) {
        return true
      }
    }
    visiting.delete(node)
    done.add(node)
    return false
  }

  for (const node of nodes) {
    if (walk(node)) {
      return true
    }
  }
  return false
}

/** 若加入 candidate 是否成环（自环也算环，对照上游 validateConnection） */
export function wouldCreateCycle(existing: PidEdge[], candidate: PidEdge): boolean {
  if (candidate.emitterPid === candidate.receiverPid) {
    return true
  }
  return hasCycle([...existing, candidate])
}

/** 完全相同的连线视为重复（同 pid + 同端口组合） */
export function isDuplicateWire(wires: WireConfiguration[], candidate: WireConfiguration): boolean {
  return wires.some(
    (wire) =>
      wire.emitterPid === candidate.emitterPid &&
      wire.emitterPort === candidate.emitterPort &&
      wire.receiverPid === candidate.receiverPid &&
      wire.receiverPort === candidate.receiverPort,
  )
}

/** 端口编号合法性：emitterPort ∈ [0, outputPortCount)，receiverPort ∈ [0, inputPortCount) */
export function isWirePortValid(
  wire: Pick<WireConfiguration, 'emitterPort' | 'receiverPort'>,
  emitterOutputPortCount: number,
  receiverInputPortCount: number,
): boolean {
  return (
    wire.emitterPort >= 0 &&
    wire.emitterPort < emitterOutputPortCount &&
    wire.receiverPort >= 0 &&
    wire.receiverPort < receiverInputPortCount
  )
}

const NUMERIC_INTEGER_TYPES = new Set(['INTEGER', 'LONG', 'BYTE'])
const NUMERIC_TYPES = new Set([...NUMERIC_INTEGER_TYPES, 'FLOAT', 'DOUBLE'])

function checkNumericBounds(
  value: number,
  param: ConfigParameter,
): ParameterValidationResult | null {
  if (
    param.min !== undefined &&
    param.min !== null &&
    param.min !== '' &&
    value < Number(param.min)
  ) {
    return { valid: false, messageKey: 'wires.validation.min', params: { min: param.min } }
  }
  if (
    param.max !== undefined &&
    param.max !== null &&
    param.max !== '' &&
    value > Number(param.max)
  ) {
    return { valid: false, messageKey: 'wires.validation.max', params: { max: param.max } }
  }
  return null
}

function checkOptionValue(param: ConfigParameter, value: string): ParameterValidationResult | null {
  if (!param.options) {
    return null
  }
  const allowed = [...Object.keys(param.options), ...Object.values(param.options)]
  return allowed.includes(value) ? null : { valid: false, messageKey: 'wires.validation.options' }
}

/** 校验单个字段值（表单中的值一律以字符串承载，与上游 metatype 渲染一致） */
export function validateParameter(
  param: ConfigParameter,
  rawValue: string | null | undefined,
): ParameterValidationResult {
  const value = rawValue?.trim() ?? ''
  if (value === '') {
    return param.required
      ? { valid: false, messageKey: 'wires.validation.required' }
      : { valid: true }
  }

  const optionFailure = checkOptionValue(param, value)
  if (optionFailure) {
    return optionFailure
  }

  if (param.type === 'BOOLEAN') {
    return value === 'true' || value === 'false'
      ? { valid: true }
      : { valid: false, messageKey: 'wires.validation.boolean' }
  }
  if (param.type === 'CHAR') {
    return value.length === 1
      ? { valid: true }
      : { valid: false, messageKey: 'wires.validation.char' }
  }
  if (NUMERIC_TYPES.has(param.type)) {
    const parsed = Number(value)
    if (Number.isNaN(parsed)) {
      return { valid: false, messageKey: 'wires.validation.number' }
    }
    if (NUMERIC_INTEGER_TYPES.has(param.type) && !Number.isInteger(parsed)) {
      return { valid: false, messageKey: 'wires.validation.integer' }
    }
    return checkNumericBounds(parsed, param) ?? { valid: true }
  }
  return { valid: true }
}

/** 校验整张配置表单，返回 每个字段id -> 校验结果 */
export function validateConfiguration(
  params: ConfigParameter[],
  values: Record<string, string>,
): Record<string, ParameterValidationResult> {
  const results: Record<string, ParameterValidationResult> = {}
  for (const param of params) {
    results[param.id] = validateParameter(param, values[param.id])
  }
  return results
}

/** 将字符串表单值按字段类型转换为配置属性值 */
export function toPropertyScalar(
  param: ConfigParameter,
  rawValue: string | null | undefined,
): string | number | boolean {
  const value = rawValue?.trim() ?? ''
  switch (param.type) {
    case 'INTEGER':
    case 'LONG':
    case 'BYTE':
    case 'FLOAT':
    case 'DOUBLE':
      return Number(value)
    case 'BOOLEAN':
      return value === 'true'
    default:
      return value
  }
}
