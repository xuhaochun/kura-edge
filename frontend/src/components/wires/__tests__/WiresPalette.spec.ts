/**
 * WiresPalette 挂载测试：分组渲染（标签走 i18n 中文，factoryPid 原样展示）、
 * 点击 create、拖拽 dataTransfer。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WiresPalette from '@/components/wires/WiresPalette.vue'
import { useMetadataStore } from '@/stores/metadata'
import { i18n } from '@/i18n'
import type { ComponentDescriptor } from '@/types/wires'

const assetApi = vi.hoisted(() => ({ getAssetInfos: vi.fn() }))

vi.mock('@/api/driversAssets', () => assetApi)

const descriptor = (overrides: Partial<ComponentDescriptor>): ComponentDescriptor => ({
  factoryPid: 'org.eclipse.kura.wires.Timer',
  name: 'Timer',
  minInputPorts: 0,
  maxInputPorts: 0,
  defaultInputPorts: 0,
  minOutputPorts: 1,
  maxOutputPorts: 1,
  defaultOutputPorts: 1,
  properties: [],
  ...overrides,
})

describe('WiresPalette', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    assetApi.getAssetInfos.mockReset()
    assetApi.getAssetInfos.mockResolvedValue([])
  })

  function mountPalette() {
    return mount(WiresPalette, { global: { plugins: [i18n] } })
  }

  it('thenRendersOneGroupPerFactoryPid', () => {
    const metadata = useMetadataStore()
    metadata.descriptors = [
      descriptor({}),
      descriptor({ factoryPid: 'org.eclipse.kura.wires.Logger', name: 'Logger' }),
    ]
    const wrapper = mountPalette()
    expect(wrapper.findAll('.wires-palette__group')).toHaveLength(2)
    const text = wrapper.text()
    expect(text).toContain('定时器')
    expect(text).toContain('日志记录器')
    expect(text).toContain('org.eclipse.kura.wires.Timer')
  })

  it('thenEmitsCreateOnItemClick', async () => {
    const metadata = useMetadataStore()
    metadata.descriptors = [descriptor({})]
    const wrapper = mountPalette()
    await wrapper.find('.wires-palette__item').trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
    expect(wrapper.emitted('create')?.[0]?.[0]).toMatchObject({
      descriptor: { factoryPid: 'org.eclipse.kura.wires.Timer' },
    })
  })

  it('thenCarriesFactoryPidOnDragStart', async () => {
    const metadata = useMetadataStore()
    metadata.descriptors = [descriptor({})]
    const setData = vi.fn()
    const wrapper = mountPalette()
    await wrapper.find('.wires-palette__item').trigger('dragstart', {
      dataTransfer: { setData, effectAllowed: undefined },
    })
    expect(setData).toHaveBeenCalledWith(
      'application/x-wires-node',
      JSON.stringify({ factoryPid: 'org.eclipse.kura.wires.Timer' }),
    )
  })

  it('thenListsExistingAssetsInDedicatedGroupAndHidesAssetFactory', async () => {
    assetApi.getAssetInfos.mockResolvedValue([
      { pid: 'asset1', factoryPid: 'org.eclipse.kura.wire.WireAsset', driverPid: 'driver1' },
    ])
    const metadata = useMetadataStore()
    metadata.descriptors = [
      descriptor({}),
      descriptor({
        factoryPid: 'org.eclipse.kura.wire.WireAsset',
        name: 'Wire Asset',
        properties: [],
      }),
    ]
    const wrapper = mountPalette()
    await flushPromises()

    expect(wrapper.findAll('.wires-palette__group')).toHaveLength(2)
    expect(wrapper.text()).toContain('资产')
    expect(wrapper.text()).toContain('asset1')

    const assetItems = wrapper.findAll('.wires-palette__group--assets .wires-palette__item')
    expect(assetItems).toHaveLength(1)
    await assetItems[0].trigger('click')
    expect(wrapper.emitted('create')?.[0]?.[0]).toMatchObject({
      descriptor: { factoryPid: 'org.eclipse.kura.wire.WireAsset' },
      pid: 'asset1',
    })
  })

  it('thenShowsEmptyStateWithoutDescriptors', () => {
    const wrapper = mountPalette()
    expect(wrapper.find('.wires-palette__empty').exists()).toBe(true)
  })
})
