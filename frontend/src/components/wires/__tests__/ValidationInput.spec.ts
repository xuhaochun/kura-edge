/** ValidationInput 挂载测试：按 metatype 渲染控件、校验态修饰类、v-model 回传 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { ConfigParameter } from '@/types/wires'

// jsdom 下脱离 VueFlow 上下文挂载：stub Handle，仅保留需要断言的接口
vi.mock('@vue-flow/core', () => ({
  Handle: {
    name: 'Handle',
    props: ['type', 'position', 'id', 'style'],
    template:
      '<div class="handle-stub" :data-type="type" :data-handle-id="id" :data-position="position" :style="style"></div>',
  },
  Position: { Left: 'left', Right: 'right' },
}))
const { Position } = await import('@vue-flow/core')

const { i18n } = await import('@/i18n')
const ValidationInput = (await import('@/components/wires/ValidationInput.vue')).default
const PortHandle = (await import('@/components/wires/PortHandle.vue')).default

const parameter = (overrides: Partial<ConfigParameter>): ConfigParameter => ({
  id: 'field',
  name: 'Field',
  type: 'STRING',
  required: false,
  cardinality: 0,
  ...overrides,
})

describe('ValidationInput', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountInput(overrides: Partial<ConfigParameter> = {}, value = '', invalid = false) {
    return mount(ValidationInput, {
      props: { param: parameter(overrides), modelValue: value, invalid },
      global: { plugins: [ElementPlus, i18n] },
    })
  }

  it('thenRendersBooleanSelectForBooleanType', () => {
    const wrapper = mountInput({ type: 'BOOLEAN' }, 'true')
    const select = wrapper.findComponent({ name: 'ElSelect' })
    expect(select.exists()).toBe(true)
    expect(select.props('modelValue')).toBe('true')
  })

  it('thenRendersOptionSelectWhenOptionsAreDeclared', () => {
    const wrapper = mountInput({ options: { '1': 'One', '2': 'Two' } }, '1')
    const select = wrapper.findComponent({ name: 'ElSelect' })
    expect(select.exists()).toBe(true)
    expect(select.props('modelValue')).toBe('1')
  })

  it('thenShowsTranslatedOptionLabelWhileKeepingTokenAsValue', () => {
    const wrapper = mountInput({ options: { SECONDS: 'SECONDS', MINUTES: 'MINUTES' } }, 'SECONDS')
    const options = wrapper.findAllComponents({ name: 'ElOption' })
    expect(options.map((option) => option.props('label'))).toEqual(['秒', '分钟'])
    expect(options.map((option) => option.props('value'))).toEqual(['SECONDS', 'MINUTES'])
  })

  it('thenRendersPasswordInputForPasswordType', () => {
    const wrapper = mountInput({ type: 'PASSWORD' }, 'secret')
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
  })

  it('thenShowsRangePlaceholderForNumericField', () => {
    const wrapper = mountInput({ type: 'INTEGER', min: '1', max: '10' })
    expect(wrapper.find('input').attributes('placeholder')).toBe('1 ~ 10')
  })

  it('thenMarksInvalidField', () => {
    const wrapper = mountInput({}, 'x', true)
    expect(wrapper.find('.config-area__input--invalid').exists()).toBe(true)
  })

  it('thenEmitsUpdatedValue', async () => {
    const wrapper = mountInput()
    await wrapper.find('input').setValue('42')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['42'])
  })
})

describe('PortHandle', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('thenRendersOneTargetHandlePerPort', () => {
    const wrapper = mount(PortHandle, { props: { direction: 'in', portCount: 3 } })
    expect(wrapper.findAll('.handle-stub[data-type="target"]')).toHaveLength(3)
    expect(wrapper.find('.handle-stub[data-handle-id="in2"]').exists()).toBe(true)
    expect(wrapper.find('.handle-stub[data-handle-id="in0"]').attributes('data-position')).toBe(
      Position.Left,
    )
  })

  it('thenRendersOneSourceHandlePerPort', () => {
    const wrapper = mount(PortHandle, { props: { direction: 'out', portCount: 2 } })
    expect(wrapper.findAll('.handle-stub[data-type="source"]')).toHaveLength(2)
    expect(wrapper.find('.handle-stub[data-handle-id="out1"]').exists()).toBe(true)
    expect(wrapper.find('.handle-stub[data-handle-id="out0"]').attributes('data-position')).toBe(
      Position.Right,
    )
  })

  it('thenRendersNothingForZeroPorts', () => {
    const wrapper = mount(PortHandle, { props: { direction: 'out', portCount: 0 } })
    expect(wrapper.findAll('.handle-stub')).toHaveLength(0)
  })

  it('thenDistributesHandlesAlongTheNodeEdge', () => {
    const wrapper = mount(PortHandle, { props: { direction: 'in', portCount: 3 } })
    const tops = wrapper.findAll('.handle-stub').map((handle) => handle.attributes('style'))
    expect(tops).toEqual([
      expect.stringContaining('25%'),
      expect.stringContaining('50%'),
      expect.stringContaining('75%'),
    ])
  })
})
