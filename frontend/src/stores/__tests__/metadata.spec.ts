/**
 * metadata store 单测：metatype AD → 表单字段转换、组件定义 → 描述符转换、
 * GET /metadata 载入（并发保护）、组件标签与默认属性取值。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Ad, WiresMetadata } from '@/types/wires'

const mocks = vi.hoisted(() => ({ getMetadata: vi.fn() }))

vi.mock('@/api/metadata', () => ({
  getMetadata: mocks.getMetadata,
  getConfigsByPids: vi.fn(),
  getFactoryPids: vi.fn(),
  getDrivers: vi.fn(),
  getAssets: vi.fn(),
  putConfigs: vi.fn(),
  deleteConfigsByPids: vi.fn(),
}))

import { toComponentDescriptor, toConfigParameter, useMetadataStore } from '@/stores/metadata'

const TIMER_FACTORY_PID = 'org.eclipse.kura.wire.Timer'

const ad = (overrides: Partial<Ad>): Ad => ({
  id: 'simple.interval',
  name: 'simple.interval',
  type: 'INTEGER',
  required: true,
  ...overrides,
})

describe('metadata store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.getMetadata.mockReset()
  })

  it('thenMapsAdToFormParameterWithStringBoundsAndOptions', () => {
    const param = toConfigParameter(
      ad({
        description: 'Interval of ticks',
        defaultValue: 10,
        min: 1,
        max: 100,
        options: [
          { label: 'Seconds', value: 'SECONDS' },
          { label: 'Minutes', value: 'MINUTES' },
        ],
      }),
    )

    expect(param.id).toBe('simple.interval')
    expect(param.description).toBe('Interval of ticks')
    expect(param.defaultValue).toBe('10')
    expect(param.min).toBe('1')
    expect(param.max).toBe('100')
    expect(param.options).toEqual({ SECONDS: 'Seconds', MINUTES: 'Minutes' })
    expect(param.cardinality).toBe(0)
  })

  it('thenNullsEmptyOptionalMetadata', () => {
    const param = toConfigParameter(ad({ type: 'STRING', required: false }))

    expect(param.description).toBeNull()
    expect(param.defaultValue).toBeNull()
    expect(param.min).toBeNull()
    expect(param.max).toBeNull()
    expect(param.options).toBeNull()
    expect(param.required).toBe(false)
  })

  it('thenDerivesDescriptorLabelFromFactoryPidTail', () => {
    const descriptor = toComponentDescriptor({
      factoryPid: TIMER_FACTORY_PID,
      minInputPorts: 0,
      maxInputPorts: 0,
      defaultInputPorts: 0,
      minOutputPorts: 1,
      maxOutputPorts: 1,
      defaultOutputPorts: 1,
      componentOcd: [ad({})],
    })

    expect(descriptor.name).toBe('Timer')
    expect(descriptor.defaultOutputPorts).toBe(1)
    expect(descriptor.inputPortNames).toBeNull()
    expect(descriptor.outputPortNames).toBeNull()
    expect(descriptor.properties).toHaveLength(1)
  })

  it('thenLoadsDescriptorsFromMetadata', async () => {
    const metadata: WiresMetadata = {
      wireComponentDefinitions: [
        {
          factoryPid: TIMER_FACTORY_PID,
          minInputPorts: 0,
          maxInputPorts: 0,
          defaultInputPorts: 0,
          minOutputPorts: 1,
          maxOutputPorts: 1,
          defaultOutputPorts: 1,
          componentOcd: [ad({})],
        },
      ],
    }
    mocks.getMetadata.mockResolvedValue(metadata)
    const store = useMetadataStore()

    await store.load()

    expect(mocks.getMetadata).toHaveBeenCalledTimes(1)
    expect(store.loaded).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.descriptors).toHaveLength(1)
    expect(store.descriptorByFactoryPid[TIMER_FACTORY_PID]?.name).toBe('Timer')
  })

  it('thenIgnoresConcurrentLoadWhileOneIsInFlight', async () => {
    let resolveMetadata: (value: WiresMetadata) => void = () => undefined
    mocks.getMetadata.mockReturnValue(
      new Promise<WiresMetadata>((resolve) => {
        resolveMetadata = resolve
      }),
    )
    const store = useMetadataStore()

    const first = store.load()
    const second = store.load()
    await Promise.resolve()
    expect(mocks.getMetadata).toHaveBeenCalledTimes(1)

    resolveMetadata({ wireComponentDefinitions: [] })
    await Promise.all([first, second])
    expect(store.loaded).toBe(true)
  })

  it('thenResetsLoadingWhenMetadataRequestFails', async () => {
    mocks.getMetadata.mockRejectedValue(new Error('boom'))
    const store = useMetadataStore()

    await expect(store.load()).rejects.toThrow('boom')
    expect(store.loading).toBe(false)
    expect(store.loaded).toBe(false)
  })

  it('thenFallsBackToFactoryPidTailWhenDescriptorHasNoName', () => {
    const store = useMetadataStore()

    expect(store.labelOf(null)).toBe('')
    expect(
      store.labelOf({
        factoryPid: TIMER_FACTORY_PID,
        name: '',
        minInputPorts: 0,
        maxInputPorts: 0,
        defaultInputPorts: 0,
        minOutputPorts: 0,
        maxOutputPorts: 0,
        defaultOutputPorts: 0,
        properties: [],
      }),
    ).toBe('Timer')
  })

  it('thenCollectsOnlyDeclaredDefaultValues', () => {
    const store = useMetadataStore()
    const descriptor = toComponentDescriptor({
      factoryPid: TIMER_FACTORY_PID,
      minInputPorts: 0,
      maxInputPorts: 0,
      defaultInputPorts: 0,
      minOutputPorts: 1,
      maxOutputPorts: 1,
      defaultOutputPorts: 1,
      componentOcd: [ad({ id: 'type', defaultValue: 'SIMPLE' }), ad({ id: 'cron.interval' })],
    })

    expect(store.defaultProperties(descriptor)).toEqual({ type: 'SIMPLE' })
    expect(store.defaultProperties(null)).toEqual({})
  })
})
