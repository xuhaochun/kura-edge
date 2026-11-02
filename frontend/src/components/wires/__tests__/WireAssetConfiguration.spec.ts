/**
 * WireAssetConfiguration 挂载测试：资产页签（字段 + driver 下拉 + 通道表）与 Driver 页签的
 * 加载、Apply 回写图 store、驱动配置独立保存。网络全部 mock。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { i18n } from '@/i18n'

const api = vi.hoisted(() => ({
  getAssetConfiguration: vi.fn(),
  getDriverConfiguration: vi.fn(),
  getDriverInfos: vi.fn(),
  putDriverConfiguration: vi.fn(),
  getMetadata: vi.fn(),
}))

vi.mock('@/api/driversAssets', () => api)
vi.mock('@/api/metadata', () => ({ getMetadata: api.getMetadata }))

import WireAssetConfiguration from '@/components/wires/WireAssetConfiguration.vue'
import { useWiresGraphStore } from '@/stores/wiresGraph'

const ASSET_PID = 'asset1'
const DRIVER_PID = 'modbus1'
const DRIVER_FACTORY_PID = 'org.eclipse.kura.driver.modbus'
const ASSET_FACTORY_PID = 'org.eclipse.kura.wire.WireAsset'

function metadataResponse() {
  return {
    wireComponentDefinitions: [
      {
        factoryPid: ASSET_FACTORY_PID,
        minInputPorts: 1,
        maxInputPorts: 1,
        defaultInputPorts: 1,
        minOutputPorts: 1,
        maxOutputPorts: 1,
        defaultOutputPorts: 1,
        componentOcd: [
          {
            id: 'asset.desc',
            name: 'asset.desc',
            type: 'STRING',
            required: false,
            defaultValue: '',
          },
          {
            id: 'driver.pid',
            name: 'driver.pid',
            type: 'STRING',
            required: true,
          },
          {
            id: 'emit.errors',
            name: 'emit.errors',
            type: 'BOOLEAN',
            required: true,
            defaultValue: false,
          },
        ],
      },
    ],
    driverOCDs: [
      {
        pid: DRIVER_FACTORY_PID,
        definition: {
          ads: [
            {
              id: 'modbus.ip.address',
              name: 'IP address',
              type: 'STRING',
              required: true,
              defaultValue: '127.0.0.1',
            },
          ],
        },
      },
    ],
    driverChannelDescriptors: [
      {
        pid: DRIVER_PID,
        factoryPid: DRIVER_FACTORY_PID,
        channelDescriptor: [
          {
            id: 'modbus.address',
            name: 'modbus.address',
            type: 'INTEGER',
            required: true,
            defaultValue: 0,
          },
        ],
      },
    ],
    assetChannelDescriptor: [
      { id: '+name', name: '+name', type: 'STRING', required: false },
      { id: '+type', name: '+type', type: 'STRING', required: true, defaultValue: 'READ' },
      {
        id: '+value.type',
        name: '+value.type',
        type: 'STRING',
        required: true,
        defaultValue: 'INTEGER',
      },
    ],
  }
}

function assetProperties() {
  return {
    'driver.pid': DRIVER_PID,
    'asset.desc': 'boiler',
    'emit.errors': false,
    'temperature#+type': 'READ',
    'temperature#+value.type': 'INTEGER',
    'temperature#modbus.address': '10',
  }
}

function givenAssetInGraph() {
  const store = useWiresGraphStore()
  store.addComponent({
    pid: ASSET_PID,
    factoryPid: ASSET_FACTORY_PID,
    inputPortCount: 1,
    outputPortCount: 1,
    position: { x: 0, y: 0 },
    properties: {},
  })
  store.select(ASSET_PID)
  return store
}

async function mountConfiguration(pinia = createPinia()) {
  setActivePinia(pinia)
  const wrapper = mount(WireAssetConfiguration, {
    props: { pid: ASSET_PID },
    global: { plugins: [pinia, i18n, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('WireAssetConfiguration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const mock of Object.values(api)) {
      mock.mockReset()
    }
    api.getMetadata.mockResolvedValue(metadataResponse())
    api.getDriverInfos.mockResolvedValue([{ pid: DRIVER_PID, factoryPid: DRIVER_FACTORY_PID }])
    api.getAssetConfiguration.mockResolvedValue(assetProperties())
    api.getDriverConfiguration.mockResolvedValue({ 'modbus.ip.address': '127.0.0.1' })
    api.putDriverConfiguration.mockResolvedValue(undefined)
  })

  it('thenLoadsSavedAssetPropertiesAndRendersDriverAndChannels', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = givenAssetInGraph()

    const wrapper = await mountConfiguration(pinia)

    expect(api.getAssetConfiguration).toHaveBeenCalledWith(ASSET_PID)
    expect(store.components[0]?.properties['driver.pid']).toBe(DRIVER_PID)
    expect(wrapper.text()).toContain('资产配置')
    expect(wrapper.text()).toContain(`Driver - ${DRIVER_PID}`)
    expect(wrapper.text()).toContain('temperature')
    expect(wrapper.text()).toContain('modbus1')
  }, 15000)

  it('thenAppliesAssetFieldsAndChannelsToTheGraphStore', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = givenAssetInGraph()
    const wrapper = await mountConfiguration(pinia)

    const description = wrapper
      .find('#pane-asset')
      .findAll('input')
      .find((input) => (input.element as HTMLInputElement).value === 'boiler')
    await description?.setValue('updated boiler')
    const applyButton = wrapper
      .find('#pane-asset')
      .findAll('button')
      .find((button) => button.text() === '应用配置')
    await applyButton?.trigger('click')
    await flushPromises()

    const properties = store.components[0]?.properties ?? {}
    expect(properties['asset.desc']).toBe('updated boiler')
    expect(properties['driver.pid']).toBe(DRIVER_PID)
    expect(properties['temperature#modbus.address']).toBe('10')
    expect(store.isDirty).toBe(true)
  })

  it('thenSavesDriverConfigurationFromTheDriverTab', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    givenAssetInGraph()
    const wrapper = await mountConfiguration(pinia)

    const driverTab = wrapper
      .findAll('.el-tabs__item')
      .find((item) => item.text().includes(`Driver - ${DRIVER_PID}`))
    await driverTab?.trigger('click')
    await flushPromises()

    const driverPane = wrapper.find('#pane-driver')
    const applyButton = driverPane.findAll('button').find((button) => button.text() === '应用配置')
    expect(applyButton?.attributes('disabled')).toBeDefined()
    const input = driverPane
      .findAll('input')
      .find((candidate) => (candidate.element as HTMLInputElement).value === '127.0.0.1')
    await input?.setValue('192.168.1.10')
    const enabledApply = driverPane.findAll('button').find((button) => button.text() === '应用配置')
    await enabledApply?.trigger('click')
    await flushPromises()

    expect(api.putDriverConfiguration).toHaveBeenCalledWith(DRIVER_PID, {
      'modbus.ip.address': '192.168.1.10',
    })
  })
})
