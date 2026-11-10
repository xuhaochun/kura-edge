/**
 * NewAssetDialog 挂载测试：驱动下拉的默认值与必填校验。
 *
 * 回归背景：此前驱动只读显示「当前选中项所属的驱动」，选中 Driver 行时该值为空，
 * 弹窗里驱动为空、点「应用」静默失败（新建 Asset 不落库，也就无法再新增检测点位）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

const { i18n } = await import('@/i18n')
const NewAssetDialog = (await import('@/components/drivers/NewAssetDialog.vue')).default

const DRIVERS = [
  { pid: 'driver1', factoryPid: 'org.eclipse.kura.driver.simulated' },
  { pid: 'driver2', factoryPid: 'org.eclipse.kura.driver.modbus' },
]

describe('NewAssetDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountDialog(defaultDriverPid: string, drivers = DRIVERS) {
    return mount(NewAssetDialog, {
      props: { modelValue: false, drivers, defaultDriverPid },
      global: { plugins: [createPinia(), i18n, ElementPlus] },
      attachTo: document.body,
    })
  }

  /** 填 Asset 名称（第一个输入框；第二个是驱动下拉的 filterable 输入） */
  async function typeName(wrapper: ReturnType<typeof mountDialog>, value: string) {
    await wrapper.findAll('.new-asset__control input')[0]!.setValue(value)
  }

  /** 表单项的 error 文案（el-form-item 的 error 常驻在 props 上，DOM 里要等过渡态） */
  function itemError(wrapper: ReturnType<typeof mountDialog>, index: number): string {
    return wrapper.findAllComponents({ name: 'ElFormItem' })[index]!.props('error') as string
  }

  function applyButton(wrapper: ReturnType<typeof mountDialog>) {
    return wrapper.findAll('button').at(-1)!
  }

  it('givenSelectedDriverWhenOpeningThenUsesItAsDefaultAndSubmitsIt', async () => {
    const wrapper = mountDialog('driver2')
    await wrapper.setProps({ modelValue: true })

    expect(wrapper.findComponent({ name: 'ElSelect' }).props('modelValue')).toBe('driver2')

    await typeName(wrapper, 'asset-new')
    await applyButton(wrapper).trigger('click')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({
      pid: 'asset-new',
      driverPid: 'driver2',
      description: '',
    })
  })

  it('givenNoMatchingDriverWhenOpeningThenFallsBackToFirstDriver', async () => {
    const wrapper = mountDialog('')
    await wrapper.setProps({ modelValue: true })

    expect(wrapper.findComponent({ name: 'ElSelect' }).props('modelValue')).toBe('driver1')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('givenNoDriversWhenApplyingThenBlocksSubmitWithHint', async () => {
    const wrapper = mountDialog('', [])
    await wrapper.setProps({ modelValue: true })

    await typeName(wrapper, 'asset-new')

    expect(applyButton(wrapper).attributes('disabled')).toBeDefined()
    expect(itemError(wrapper, 1)).toBe('请先选择该资产所属的 Driver')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('givenInvalidPidWhenApplyingThenBlocksSubmit', async () => {
    const wrapper = mountDialog('driver1')
    await wrapper.setProps({ modelValue: true })

    await typeName(wrapper, 'bad pid')

    expect(applyButton(wrapper).attributes('disabled')).toBeDefined()
    expect(itemError(wrapper, 0)).toBe('必须是合法的 service.pid')
  })
})
