/**
 * driversAssets store 单测：列表分组、选中加载、通道表编辑与序列化应用、
 * 数据页状态推导与写回、删除后的选中清理。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Ad } from '@/types/wires'
import type { AssetChannelDto } from '@/types/driversAssets'

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

vi.mock('@/api/driversAssets', () => ({
  getDriverInfos: api.getDriverInfos,
  getAssetInfos: api.getAssetInfos,
  getDriverConfiguration: api.getDriverConfiguration,
  getAssetConfiguration: api.getAssetConfiguration,
  getAssetChannelDescriptor: api.getAssetChannelDescriptor,
  putAssetConfiguration: api.putAssetConfiguration,
  putDriverConfiguration: api.putDriverConfiguration,
  createDriver: api.createDriver,
  createAsset: api.createAsset,
  deleteDriver: api.deleteDriver,
  deleteAsset: api.deleteAsset,
  getAssetChannels: api.getAssetChannels,
  readChannels: api.readChannels,
  writeChannels: api.writeChannels,
}))

vi.mock('@/api/metadata', () => ({
  getMetadata: api.getMetadata,
  getConfigsByPids: vi.fn(),
  getFactoryPids: vi.fn(),
  getDrivers: vi.fn(),
  getAssets: vi.fn(),
  putConfigs: vi.fn(),
  deleteConfigsByPids: vi.fn(),
}))

import {
  computeStatus,
  toDriverParameter,
  toFormValues,
  toPropertyMap,
  useDriversAssetsStore,
} from '@/stores/driversAssets'

const DRIVER_PID = 'driver-1'
const ASSET_PID = 'asset-1'
const DRIVER_FACTORY = 'org.eclipse.kura.driver.simulated'
const ASSET_FACTORY = 'org.eclipse.kura.wire.WireAsset'

const ad = (overrides: Partial<Ad>): Ad => ({
  id: 'simulated.description',
  name: 'simulated.description',
  type: 'STRING',
  required: false,
  ...overrides,
})

const baseDescriptor: Ad[] = [
  ad({ id: '+enabled', name: 'enabled', type: 'BOOLEAN', required: true, defaultValue: true }),
  ad({ id: '+name', name: 'name', required: true, defaultValue: 'Channel-1' }),
  ad({
    id: '+type',
    name: 'type',
    required: true,
    defaultValue: 'READ',
    options: [
      { label: 'READ', value: 'READ' },
      { label: 'READ_WRITE', value: 'READ_WRITE' },
    ],
  }),
  ad({ id: '+value.type', name: 'value.type', required: true, defaultValue: 'INTEGER' }),
]

const assetProperties = {
  'driver.pid': DRIVER_PID,
  'asset.desc': 'demo',
  'ch1#+type': 'READ',
  'ch1#+value.type': 'INTEGER',
}

const channels: AssetChannelDto[] = [
  {
    name: 'ch1',
    type: 'READ',
    valueType: 'INTEGER',
    scaleOffsetType: 'DEFINED_BY_VALUE_TYPE',
    enabled: true,
  },
  {
    name: 'setpoint',
    type: 'READ_WRITE',
    valueType: 'INTEGER',
    scaleOffsetType: 'DEFINED_BY_VALUE_TYPE',
    enabled: true,
  },
]

describe('driversAssets store', () => {
  let store: ReturnType<typeof useDriversAssetsStore>

  beforeEach(() => {
    setActivePinia(createPinia())
    store = useDriversAssetsStore()
    for (const mock of Object.values(api)) {
      mock.mockReset()
    }
    api.getDriverInfos.mockResolvedValue([{ pid: DRIVER_PID, factoryPid: DRIVER_FACTORY }])
    api.getAssetInfos.mockResolvedValue([
      { pid: ASSET_PID, factoryPid: ASSET_FACTORY, driverPid: DRIVER_PID, description: 'demo' },
    ])
    api.getMetadata.mockResolvedValue({
      wireComponentDefinitions: [],
      driverOCDs: [{ pid: DRIVER_FACTORY, definition: { ads: [ad({})] } }],
      driverChannelDescriptors: [
        {
          pid: DRIVER_PID,
          factoryPid: DRIVER_FACTORY,
          channelDescriptor: [ad({ id: 'simulated.register' })],
        },
      ],
      assetChannelDescriptor: baseDescriptor,
    })
    api.getAssetConfiguration.mockResolvedValue(assetProperties)
    api.getAssetChannelDescriptor.mockResolvedValue([
      ...baseDescriptor,
      ad({
        id: 'simulated.register',
        name: 'simulated.register',
        required: true,
        defaultValue: 'r',
      }),
    ])
    api.getDriverConfiguration.mockResolvedValue({ 'simulated.description': 'demo' })
    api.putAssetConfiguration.mockResolvedValue(undefined)
    api.putDriverConfiguration.mockResolvedValue(undefined)
    api.deleteAsset.mockResolvedValue(undefined)
    api.deleteDriver.mockResolvedValue(undefined)
    api.getAssetChannels.mockResolvedValue(channels)
    api.readChannels.mockResolvedValue([
      { name: 'ch1', status: 'SUCCESS', value: 21 },
      { name: 'setpoint', status: 'FAILURE', exceptionMessage: 'read failed' },
    ])
    api.writeChannels.mockResolvedValue([{ name: 'ch1', status: 'SUCCESS', value: 42 }])
  })

  it('thenGroupsAssetsUnderTheirDriverAfterLoad', async () => {
    await store.load()

    expect(store.drivers).toEqual([{ pid: DRIVER_PID, factoryPid: DRIVER_FACTORY }])
    expect(store.assetsByDriver[DRIVER_PID].map((asset) => asset.pid)).toEqual([ASSET_PID])
    expect(store.driverDefinitions[DRIVER_FACTORY]).toHaveLength(1)
    expect(store.baseChannelDescriptor).toHaveLength(4)
  })

  it('thenLoadsConfigurationAndDescriptorWhenSelectingAsset', async () => {
    await store.load()

    await store.select('asset', ASSET_PID)
    store.reloadChannelRows()

    expect(store.selectedAssetPid).toBe(ASSET_PID)
    expect(store.channelRows.map((row) => row.name)).toEqual(['ch1'])
    expect(store.assetColumns.map((column) => column.id)).toContain('simulated.register')
    expect(store.dirty).toBe(false)
  })

  it('thenLoadsDriverConfigurationWhenSelectingDriver', async () => {
    await store.load()

    await store.select('driver', DRIVER_PID)

    expect(api.getDriverConfiguration).toHaveBeenCalledWith(DRIVER_PID)
    expect(store.driverParameters.map((parameter) => parameter.id)).toEqual([
      'simulated.description',
    ])
  })

  it('thenMarksDirtyAndAppliesSerializedProperties', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    store.reloadChannelRows()

    store.setCell(0, '+type', 'READ_WRITE')
    expect(store.dirty).toBe(true)
    expect(store.canApply()).toBe(true)

    await store.applyAssetConfiguration()

    const [, properties] = api.putAssetConfiguration.mock.calls[0]
    expect(properties['ch1#+type']).toBe('READ_WRITE')
    expect(properties['driver.pid']).toBe(DRIVER_PID)
    expect(store.dirty).toBe(false)
  })

  it('thenAddsChannelFromDescriptorDefaults', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    store.reloadChannelRows()

    const name = store.addChannel()

    expect(name).toBe('Channel-1')
    expect(store.channelRows).toHaveLength(2)
    expect(store.channelRows[1].params['simulated.register']).toBe('r')
    expect(store.dirty).toBe(true)
  })

  it('thenRemovesSelectedChannel', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    store.reloadChannelRows()

    store.removeChannel(0)

    expect(store.channelRows).toHaveLength(0)
    expect(store.dirty).toBe(true)
  })

  it('thenComputesDataRowStatusesOnRead', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)

    await store.readAssetData()

    expect(api.readChannels).toHaveBeenCalledWith(ASSET_PID, ['ch1', 'setpoint'])
    const statuses = store.dataRows.map((row) => row.status)
    expect(statuses).toEqual(['SUCCESS', 'FAILURE'])
    expect(store.dataRows[0].edited).toBe('21')
    expect(store.dataRows[1].exceptionMessage).toBe('read failed')
    expect(store.dataLoaded).toBe(true)
  })

  it('thenWritesOnlyModifiedRows', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    await store.readAssetData()

    store.setDataValue(0, '42')
    expect(store.dataRows[0].status).toBe('MODIFIED')
    expect(store.hasModifiedValues).toBe(true)

    await store.writeAssetData()

    const [, requests] = api.writeChannels.mock.calls[0]
    expect(requests).toEqual([{ name: 'ch1', type: 'INTEGER', value: '42' }])
    expect(store.dataRows[0].status).toBe('SUCCESS')
  })

  it('thenKeepsModifiedRowsWhenWriteFails', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    await store.readAssetData()
    store.setDataValue(0, '42')
    api.writeChannels.mockRejectedValue(new Error('boom'))

    await store.writeAssetData()

    expect(store.dataError).toBe('boom')
    expect(store.hasModifiedValues).toBe(true)
  })

  it('thenRecordsReadErrorsWithoutRows', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    api.getAssetChannels.mockRejectedValue(new Error('asset not found'))

    await store.readAssetData()

    expect(store.dataError).toBe('asset not found')
    expect(store.dataRows).toEqual([])
  })

  it('thenClearsSelectionAfterDelete', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)

    await store.removeSelected()

    expect(api.deleteAsset).toHaveBeenCalledWith(ASSET_PID)
    expect(store.selected).toBeNull()
  })

  it('thenReportsUnsavedChangesForDirtyFormOrModifiedData', async () => {
    await store.load()
    await store.select('asset', ASSET_PID)
    store.reloadChannelRows()
    expect(store.hasUnsavedChanges).toBe(false)

    store.setCell(0, '+type', 'READ_WRITE')
    expect(store.hasUnsavedChanges).toBe(true)
  })

  it('thenConvertsDriverFormValuesToPropertiesWithRealBooleans', () => {
    const parameters = [
      toDriverParameter(ad({ id: 'emit.errors', type: 'BOOLEAN', defaultValue: false })),
    ]
    const values = toFormValues({ 'emit.errors': true }, parameters)

    expect(values['emit.errors']).toBe('true')
    expect(
      toPropertyMap({ 'emit.errors': true, other: 1 }, parameters, { 'emit.errors': 'false' }),
    ).toEqual({
      'emit.errors': false,
      other: 1,
    })
  })

  it('thenComputesStatusInUpstreamOrder', () => {
    const channel = channels[0]
    expect(computeStatus({ ...channel, enabled: false }, undefined, false)).toBe('DISABLED')
    expect(computeStatus(channel, undefined, true)).toBe('MODIFIED')
    expect(computeStatus(channel, undefined, false)).toBe('UNKNOWN')
    expect(computeStatus(channel, { name: 'ch1', status: 'FAILURE' }, false)).toBe('FAILURE')
    expect(computeStatus(channel, { name: 'ch1', status: 'SUCCESS', value: 1 }, false)).toBe(
      'SUCCESS',
    )
  })
})
