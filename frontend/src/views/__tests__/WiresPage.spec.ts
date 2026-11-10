/**
 * WiresPage 布局冒烟测试：左侧「组件面板」列已移除，面板内容并入右侧「组件配置」列，
 * 顺序为组件清单在上、选中组件的配置表单在下；清单项仍能把组件加入图中。
 *
 * 网络全部 mock（页面只负责编排），画布用替身挂载：本文件断言的是布局与面板接线，
 * 画布内部行为由 WiresComposer 自己的测试覆盖。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import type { Stubs } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createWebHistory } from 'vue-router'
import { i18n } from '@/i18n'
import { useWiresGraphStore } from '@/stores/wiresGraph'

const api = vi.hoisted(() => ({
  getMetadata: vi.fn(),
  getAssetInfos: vi.fn(),
  getSnapshot: vi.fn(),
  getConfigsByPids: vi.fn(),
  getAssetConfiguration: vi.fn(),
}))

vi.mock('@/api/metadata', () => ({
  getMetadata: api.getMetadata,
  getConfigsByPids: api.getConfigsByPids,
  getFactoryPids: vi.fn(),
  getDrivers: vi.fn(),
  getAssets: vi.fn(),
  putConfigs: vi.fn(),
  deleteConfigsByPids: vi.fn(),
}))

vi.mock('@/api/wireGraph', () => ({
  getSnapshot: api.getSnapshot,
  putSnapshot: vi.fn(),
  deleteGraph: vi.fn(),
}))

vi.mock('@/api/driversAssets', () => ({
  getAssetInfos: api.getAssetInfos,
  getAssetConfiguration: api.getAssetConfiguration,
  getDriverInfos: vi.fn(),
  createDriver: vi.fn(),
  createAsset: vi.fn(),
  putAssetConfiguration: vi.fn(),
  getDriverConfiguration: vi.fn(),
  putDriverConfiguration: vi.fn(),
  deleteDriver: vi.fn(),
  deleteAsset: vi.fn(),
  getAssetChannelDescriptor: vi.fn(),
  getAssetChannels: vi.fn(),
  readAllChannels: vi.fn(),
  readChannels: vi.fn(),
  writeChannels: vi.fn(),
}))

const TIMER_FACTORY_PID = 'org.eclipse.kura.wires.Timer'

/** GET /metadata 的最小回包：一个 Timer 组件定义（面板据此渲染分组） */
const TIMER_DEFINITION = {
  factoryPid: TIMER_FACTORY_PID,
  minInputPorts: 0,
  maxInputPorts: 0,
  defaultInputPorts: 0,
  minOutputPorts: 1,
  maxOutputPorts: 1,
  defaultOutputPorts: 1,
  componentOcd: [],
}

/** 路由出口宿主：页面通过 router-view 挂载，onBeforeRouteLeave 才有路由上下文 */
const Host = defineComponent({ template: '<router-view />' })

function createTestRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [{ path: '/wires', name: 'wires', component: () => import('@/views/WiresPage.vue') }],
  })
}

describe('WiresPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const mock of Object.values(api)) {
      mock.mockReset()
    }
    api.getMetadata.mockResolvedValue({
      wireComponentDefinitions: [TIMER_DEFINITION],
      driverOCDs: [],
      driverChannelDescriptors: [],
      assetChannelDescriptor: [],
    })
    api.getAssetInfos.mockResolvedValue([])
    api.getSnapshot.mockResolvedValue([])
    api.getConfigsByPids.mockResolvedValue([])
    api.getAssetConfiguration.mockResolvedValue({})
  })

  async function mountPage(stubs: Stubs = { WiresComposer: true }) {
    const router = createTestRouter()
    await router.push('/wires')
    await router.isReady()
    const wrapper = mount(Host, {
      global: { plugins: [createPinia(), i18n, ElementPlus, router], stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('thenKeepsOnlySaveButtonInToolbar', async () => {
    const wrapper = await mountPage()

    const toolbar = wrapper.find('.wires-page__toolbar')
    const buttons = toolbar.findAll('button')
    // 需求：工具栏只保留「保存」，重新加载/导入导出/删除整图/删除组件/缩放按钮全部移除
    expect(buttons).toHaveLength(1)
    expect(buttons[0].text()).toBe(i18n.global.t('wires.toolbar.save'))
    expect(toolbar.find('input[type="file"]').exists()).toBe(false)
  })

  it('thenRemovesLeftPaletteColumn', async () => {
    const wrapper = await mountPage()

    expect(wrapper.find('.wires-page__left').exists()).toBe(false)
  })

  it('thenShowsPaletteOnlyWithoutSelection', async () => {
    const wrapper = await mountPage()

    const right = wrapper.find('.wires-page__right')
    expect(right.exists()).toBe(true)

    // 未选中组件：右栏只有组件面板（对照上游 configurationRow 默认隐藏），且面板吃满整列
    const columns = Array.from(right.element.children).map((element) => element.className)
    expect(columns).toEqual(['wires-page__palette wires-page__palette--expanded'])
    expect(right.find('.config-area').exists()).toBe(false)
  })

  it('thenRendersPaletteAboveConfigurationAfterSelection', async () => {
    const wrapper = await mountPage()
    const store = useWiresGraphStore()

    await wrapper.find('.wires-page__right .wires-palette__item').trigger('click')
    await flushPromises()
    const pid = store.components[0]?.pid ?? ''
    store.select(pid)
    await flushPromises()

    const right = wrapper.find('.wires-page__right')
    // 直接子元素顺序即视觉顺序：组件清单在上，选中组件的配置表单在下
    const columns = Array.from(right.element.children).map((element) => element.className)
    expect(columns).toEqual(['wires-page__palette', 'wires-page__config'])
    expect(right.find('.wires-page__palette .wires-palette').exists()).toBe(true)
    expect(right.find('.wires-page__config .config-area').exists()).toBe(true)
  })

  it('thenAddsComponentFromPaletteInRightColumn', async () => {
    const wrapper = await mountPage()
    const store = useWiresGraphStore()
    expect(store.components).toHaveLength(0)

    await wrapper.find('.wires-page__right .wires-palette__item').trigger('click')
    await flushPromises()

    expect(store.components.map((component) => component.factoryPid)).toEqual([TIMER_FACTORY_PID])
  })
})
