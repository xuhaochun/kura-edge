/**
 * useMetadataText 单测：组件名 / 属性标签 / 属性说明 / 选项标签的中文查表与回落。
 * 组合式函数依赖 useI18n 注入，故按组件测试的挂载方式，用宿主组件提供 i18n 上下文后调用。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { i18n } from '@/i18n'
import { useMetadataText } from '@/composables/useMetadataText'
import type { ConfigParameter } from '@/types/wires'

/** 上游 Timer 组件的 factoryPid（元数据里 factoryPid 末段即查表键） */
const TIMER_FACTORY_PID = 'org.eclipse.kura.wire.Timer'

const parameter = (overrides: Partial<ConfigParameter>): ConfigParameter => ({
  id: 'field',
  name: 'field',
  type: 'STRING',
  required: false,
  cardinality: 0,
  ...overrides,
})

/** useMetadataText 的对外接口（宿主组件暴露后的取用形态） */
interface MetadataTextApi {
  componentLabel: (factoryPid: string, fallback: string) => string
  propertyLabel: (param: ConfigParameter, factoryPid: string) => string
  propertyDescription: (param: ConfigParameter, factoryPid: string) => string
  optionLabel: (label: string) => string
}

const Host = defineComponent({
  setup() {
    return { text: useMetadataText() }
  },
  template: '<div />',
})

describe('useMetadataText', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function api(): MetadataTextApi {
    const wrapper = mount(Host, { global: { plugins: [i18n] } })
    return (wrapper.vm as unknown as { text: MetadataTextApi }).text
  }

  it('thenTranslatesRegisteredComponentLabel', () => {
    expect(api().componentLabel(TIMER_FACTORY_PID, 'Timer')).toBe('定时器')
  })

  it('thenFallsBackToBackendLabelForUnknownComponent', () => {
    expect(api().componentLabel('com.acme.Custom', 'Custom')).toBe('Custom')
  })

  it('thenTranslatesPropertyWithDottedId', () => {
    const param = parameter({ id: 'simple.interval', name: 'simple.interval' })
    expect(api().propertyLabel(param, TIMER_FACTORY_PID)).toBe('间隔时长')
    expect(api().propertyDescription(param, TIMER_FACTORY_PID)).toContain('触发')
  })

  it('thenFallsBackToMetatypeTextForUnknownProperty', () => {
    const param = parameter({
      id: 'unknown.prop',
      name: 'Unknown Prop',
      description: 'Upstream text',
    })
    expect(api().propertyLabel(param, TIMER_FACTORY_PID)).toBe('Unknown Prop')
    expect(api().propertyDescription(param, TIMER_FACTORY_PID)).toBe('Upstream text')
  })

  it('thenTranslatesKnownOptionLabelAndKeepsUnknownToken', () => {
    expect(api().optionLabel('SECONDS')).toBe('秒')
    expect(api().optionLabel('RETAIN')).toBe('保留匹配项')
    expect(api().optionLabel('NOT_A_TOKEN')).toBe('NOT_A_TOKEN')
  })
})
