/**
 * 组件目录文案的本地化（对照上游 metatype 的英文 name / description / options）。
 *
 * 后端 GET /metadata 只提供英文 metatype 文案，而组件面板与配置表单需要中文：
 * 按「factoryPid 末段 + 属性 id + 选项英文标签」查 i18n 的 `wires.metadata.*` 表，
 * 查不到时回落后端原文 —— 异构快照里本后端不认识的组件仍能原样显示与配置
 * （与「可直接导入 Kura 原生快照」的兼容目标一致）。
 *
 * 属性 id 本身含点号（如 simple.interval），若整体作为 key 传入会被 vue-i18n
 * 当作点号路径解析，故属性表经 `tm()` 整表取出后按键直取；同理组件名按
 * factoryPid 末段查表（不依赖完整 pid），上游与测试里的 pid 前缀差异不影响命中。
 * 存在性判断也不能用 `te()`：它只认可编译的消息，值为对象的键会一律返回 false。
 */
import { useI18n } from 'vue-i18n'
import type { ConfigParameter } from '@/types/wires'

/** 单个属性的中文文案 */
interface PropertyText {
  name?: string
  description?: string
}

/** 单个组件的中文文案表 */
interface ComponentText {
  name?: string
  properties?: Record<string, PropertyText>
}

/** factoryPid 末段（对照上游按类名展示组件的做法）：org.eclipse.kura.wire.Timer -> Timer */
function factoryPidSuffix(factoryPid: string): string {
  const segments = factoryPid.split('.')
  return segments[segments.length - 1] ?? factoryPid
}

/**
 * 文案表判定：vue-i18n 的 `te()` 只认可编译的消息（字符串 / 函数），
 * 对「值是对象」的键一律返回 false，故这里不能用 te 做存在性判断 ——
 * 未登记的键由 `tm()` 返回空对象，调用方按键取不到值即回落后端原文。
 */
function isComponentText(value: unknown): value is ComponentText {
  return typeof value === 'object' && value !== null
}

export function useMetadataText() {
  const { tm } = useI18n()

  /** 取组件文案表；未登记翻译时为空对象（调用方回落后端原文） */
  function componentText(factoryPid: string): ComponentText {
    const text: unknown = tm(`wires.metadata.components.${factoryPidSuffix(factoryPid)}`)
    return isComponentText(text) ? text : {}
  }

  /** 属性文案表（键为 metatype AD id，含点号，故按键直取） */
  function propertyTexts(factoryPid: string): Record<string, PropertyText> {
    return componentText(factoryPid).properties ?? {}
  }

  /**
   * 组件显示名（组件面板项与画布节点）。
   *
   * @param factoryPid 组件的 factoryPid
   * @param fallback 未登记翻译时的兜底名（后端名称，通常为 factoryPid 末段）
   */
  function componentLabel(factoryPid: string, fallback: string): string {
    return componentText(factoryPid).name ?? fallback
  }

  /**
   * 表单字段标签。
   *
   * @param param 字段定义（来自 GET /metadata 的 properties 项）
   * @param factoryPid 字段所属组件的 factoryPid
   */
  function propertyLabel(param: ConfigParameter, factoryPid: string): string {
    return propertyTexts(factoryPid)[param.id]?.name ?? param.name
  }

  /**
   * 表单字段说明（label 的 tooltip）。
   *
   * @param param 字段定义（来自 GET /metadata 的 properties 项）
   * @param factoryPid 字段所属组件的 factoryPid
   */
  function propertyDescription(param: ConfigParameter, factoryPid: string): string {
    return propertyTexts(factoryPid)[param.id]?.description ?? param.description ?? ''
  }

  /**
   * 下拉选项显示名：按后端 metatype 的英文标签查表（如 SECONDS / RETAIN），
   * 未登记时原样显示；选项的取值（value）始终不变，配置兼容性不受影响。
   *
   * @param label 后端 metatype 的选项标签
   */
  function optionLabel(label: string): string {
    const options = tm('wires.metadata.options') as unknown as Record<string, string>
    return options[label] ?? label
  }

  return { componentLabel, propertyLabel, propertyDescription, optionLabel }
}
