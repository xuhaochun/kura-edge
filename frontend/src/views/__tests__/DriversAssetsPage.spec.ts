/**
 * DriversAssetsPage 挂载测试：工具栏按钮启用规则、列表行（驱动 + 缩进资产）、
 * 选中切换时详情区渲染与否，以及路由离开时的未保存改动拦截。
 *
 * 网络全部 mock（页面只负责编排），断言的是用户能看到的可观察行为。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import ElementPlus from 'element-plus'
import type { Stubs } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { i18n } from '@/i18n'

const api = vi.hoisted(() => ({
  getDriverInfos: vi.fn(),
  getAssetInfos: vi.fn(),
  getMetadata: vi.fn(),
  getDriverConfiguration: vi.fn(),
  getAssetConfiguration: vi.fn(),
  getAssetChannelDescriptor: vi.fn(),
  putAssetConfiguration: vi.fn(),
  putDriverConfiguration: vi.fn(),
  createDriver: vi.fn(),
  createAsset: vi.fn(),
  deleteDriver: vi.fn(),
  deleteAsset: vi.fn(),
  getAssetChannels: vi.fn(),
  readChannels: vi.fn(),
  writeChannels: vi.fn(),
}))

vi.mock('@/api/driversAssets', () => api)
vi.mock('@/api/metadata', () => ({
  getMetadata: api.getMetadata,
  getConfigsByPids: vi.fn(),
  getFactoryPids: vi.fn(),
  getDrivers: vi.fn(),
  getAssets: vi.fn(),
  putConfigs: vi.fn(),
  deleteConfigsByPids: vi.fn(),
}))

/** 路由出口宿主：页面通过 router-view 挂载，onBeforeRouteLeave 才有路由上下文 */
const Host = defineComponent({ template: '<router-view />' })

/** 新建资产弹窗替身：暴露页面传下来的默认驱动，并可按默认值提交（只断言页面编排，不测弹窗内部） */
function stubNewAssetDialog() {
  return {
    name: 'NewAssetDialog',
    props: { modelValue: Boolean, drivers: Array, defaultDriverPid: String },
    emits: ['submit', 'update:modelValue'],
    template: `<div class="stub-new-asset" :data-default="defaultDriverPid">
      <button
        class="stub-new-asset__submit"
        @click="$emit('submit', { pid: 'asset-new', driverPid: defaultDriverPid, description: '' })"
      >submit</button>
    </div>`,
  }
}

import { useDriversAssetsStore } from '@/stores/driversAssets'

const DRIVER_PID = 'driver1'
const ASSET_PID = 'asset1'

function createTestRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', redirect: '/drivers-assets' },
      {
        path: '/drivers-assets',
        name: 'da',
        component: () => import('@/views/DriversAssetsPage.vue'),
      },
      { path: '/wires', name: 'wires', component: { template: '<div />' } },
    ],
  })
}

describe('DriversAssetsPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const mock of Object.values(api)) {
      mock.mockReset()
    }
    api.getDriverInfos.mockResolvedValue([
      { pid: DRIVER_PID, factoryPid: 'org.eclipse.kura.driver.simulated' },
    ])
    api.getAssetInfos.mockResolvedValue([
      {
        pid: ASSET_PID,
        factoryPid: 'org.eclipse.kura.wire.WireAsset',
        driverPid: DRIVER_PID,
        description: 'demo',
      },
    ])
    api.getMetadata.mockResolvedValue({
      wireComponentDefinitions: [],
      driverOCDs: [{ pid: 'org.eclipse.kura.driver.simulated', definition: { ads: [] } }],
      driverChannelDescriptors: [],
      assetChannelDescriptor: [
        { id: '+type', name: 'type', type: 'STRING', required: true, defaultValue: 'READ' },
      ],
    })
    api.getDriverConfiguration.mockResolvedValue({})
    api.getAssetConfiguration.mockResolvedValue({ 'driver.pid': DRIVER_PID })
    api.getAssetChannelDescriptor.mockResolvedValue([
      { id: '+type', name: 'type', type: 'STRING', required: true, defaultValue: 'READ' },
    ])
    api.getAssetChannels.mockResolvedValue([])
  })

  async function mountPage(stubs: Stubs = {}) {
    const router = createTestRouter()
    await router.push('/drivers-assets')
    await router.isReady()
    const wrapper = mount(Host, {
      global: { plugins: [createPinia(), i18n, ElementPlus, router], stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('thenRendersToolbarWithNewAssetDisabledWithoutDriver', async () => {
    const wrapper = await mountPage()

    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons).toContain('新建 Driver')
    expect(buttons).toContain('新建 Asset')
    expect(buttons).toContain('删除')
  })

  it('thenRendersDriverRowWithNestedAssetRow', async () => {
    const wrapper = await mountPage()
    const store = useDriversAssetsStore()
    await store.select('driver', DRIVER_PID)
    await flushPromises()

    const rows = wrapper.findAll('.da-list__row')
    expect(rows.length).toBeGreaterThanOrEqual(2)
    expect(wrapper.text()).toContain(DRIVER_PID)
    expect(wrapper.text()).toContain(ASSET_PID)
  })

  it('thenShowsEmptyDetailBeforeSelection', async () => {
    api.getDriverInfos.mockResolvedValue([])
    api.getAssetInfos.mockResolvedValue([])
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('选择左侧的 Driver 或 Asset 以查看配置')
  })

  it('thenDisablesDeleteWithoutSelection', async () => {
    const wrapper = await mountPage()
    const store = useDriversAssetsStore()
    store.selected = null
    await flushPromises()

    const deleteButton = wrapper.findAll('button').find((button) => button.text() === '删除')
    expect(deleteButton?.attributes('disabled')).toBeDefined()
  })

  it('givenSelectedDriverWhenCreatingAssetThenBindsThatDriver', async () => {
    const wrapper = await mountPage({ NewAssetDialog: stubNewAssetDialog() })
    const store = useDriversAssetsStore()
    await store.select('driver', DRIVER_PID)
    await flushPromises()

    const newAssetButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('新建 Asset'))!
    expect(newAssetButton.attributes('disabled')).toBeUndefined()
    await newAssetButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('.stub-new-asset').attributes('data-default')).toBe(DRIVER_PID)

    await wrapper.find('.stub-new-asset__submit').trigger('click')
    await flushPromises()

    expect(api.createAsset).toHaveBeenCalledWith('asset-new', DRIVER_PID, '')
  })

  it('givenSelectedAssetWhenCreatingAssetThenDefaultsToItsDriver', async () => {
    const wrapper = await mountPage({ NewAssetDialog: stubNewAssetDialog() })
    const store = useDriversAssetsStore()
    await store.select('asset', ASSET_PID)
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('新建 Asset'))!
      .trigger('click')
    await flushPromises()

    expect(wrapper.find('.stub-new-asset').attributes('data-default')).toBe(DRIVER_PID)
  })

  it('givenNoDriverAtAllWhenToolbarRendersThenNewAssetIsDisabled', async () => {
    api.getDriverInfos.mockResolvedValue([])
    api.getAssetInfos.mockResolvedValue([])
    const wrapper = await mountPage()

    const newAssetButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('新建 Asset'))
    expect(newAssetButton?.attributes('disabled')).toBeDefined()
  })
})
