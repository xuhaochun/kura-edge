/**
 * useSnapshot 单测：图 JSON（不含组件配置）导入时按 pid 向后端补齐 factoryPid 与属性；
 * 补不齐的组件给出明确提示；保存前拦截缺 factoryPid 的组件。
 * 组合式函数依赖 useI18n 注入，故用宿主组件提供上下文。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import type {
  ComponentConfigurationDto,
  ComponentDescriptor,
  WireGraphSnapshot,
} from '@/types/wires'

const mocks = vi.hoisted(() => ({
  getSnapshot: vi.fn(),
  putSnapshot: vi.fn(),
  deleteGraph: vi.fn(),
  getConfigsByPids: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}))

vi.mock('@/api/wireGraph', () => ({
  getSnapshot: mocks.getSnapshot,
  putSnapshot: mocks.putSnapshot,
  deleteGraph: mocks.deleteGraph,
}))

vi.mock('@/api/metadata', () => ({
  getConfigsByPids: mocks.getConfigsByPids,
  getMetadata: vi.fn(),
  getFactoryPids: vi.fn(),
  getDrivers: vi.fn(),
  getAssets: vi.fn(),
  putConfigs: vi.fn(),
  deleteConfigsByPids: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: mocks.error, success: mocks.success, warning: mocks.warning },
}))

import { i18n } from '@/i18n'
import { useSnapshot } from '@/composables/useSnapshot'
import { useWiresGraphStore } from '@/stores/wiresGraph'

const TIMER_FACTORY_PID = 'org.eclipse.kura.wire.Timer'
const GRAPH_SERVICE_PID = 'org.eclipse.kura.wire.graph.WireGraphService'
const SERVICE_FACTORY_PID = 'service.factoryPid'

interface SnapshotApi {
  importJson: (file: File) => Promise<void>
  save: () => Promise<void>
  load: () => Promise<void>
  deleteAll: () => Promise<void>
  exportJson: () => void
  createComponent: (descriptor: ComponentDescriptor, position: { x: number; y: number }) => void
}

const Host = defineComponent({
  setup() {
    return { snapshot: useSnapshot() }
  },
  template: '<div />',
})

const graphOnly = (): WireGraphSnapshot => ({
  components: [{ pid: 'Timer', inputPortCount: 0, outputPortCount: 1 }],
  wires: [],
})

const timerConfig = (): ComponentConfigurationDto => ({
  pid: 'Timer',
  properties: {
    [SERVICE_FACTORY_PID]: { type: 'STRING', value: TIMER_FACTORY_PID },
    'simple.interval': { type: 'INTEGER', value: 10 },
  },
})

const snapshotFile = (content: unknown): File =>
  new File([JSON.stringify(content)], 'snapshot.json', { type: 'application/json' })

describe('useSnapshot', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    Object.values(mocks).forEach((mock) => mock.mockReset())
  })

  function api(): SnapshotApi {
    const wrapper = mount(Host, { global: { plugins: [i18n] } })
    return (wrapper.vm as unknown as { snapshot: SnapshotApi }).snapshot
  }

  it('thenRecoversComponentConfigurationForGraphOnlyImport', async () => {
    mocks.getConfigsByPids.mockResolvedValue([timerConfig()])
    const store = useWiresGraphStore()

    await api().importJson(snapshotFile(graphOnly()))

    expect(mocks.getConfigsByPids).toHaveBeenCalledWith(['Timer'])
    expect(store.components).toHaveLength(1)
    expect(store.components[0]?.factoryPid).toBe(TIMER_FACTORY_PID)
    expect(store.components[0]?.properties['simple.interval']).toBe(10)
    expect(store.isDirty).toBe(true)
    expect(mocks.success).toHaveBeenCalled()
    expect(mocks.warning).not.toHaveBeenCalled()
  })

  it('thenWarnsAboutComponentsThatCannotBeRecovered', async () => {
    mocks.getConfigsByPids.mockResolvedValue([])
    const store = useWiresGraphStore()

    await api().importJson(snapshotFile(graphOnly()))

    expect(store.components[0]?.factoryPid).toBe('')
    expect(mocks.warning).toHaveBeenCalledWith(expect.stringContaining('Timer'))
  })

  it('thenSkipsRecoveryWhenTheFileAlreadyCarriesConfigurations', async () => {
    mocks.getConfigsByPids.mockResolvedValue([])

    await api().importJson(
      snapshotFile({
        configs: [
          {
            pid: GRAPH_SERVICE_PID,
            properties: {
              WireGraph: { type: 'STRING', value: JSON.stringify(graphOnly()) },
            },
          },
          timerConfig(),
        ],
      }),
    )

    expect(mocks.getConfigsByPids).not.toHaveBeenCalled()
  })

  it('thenBlocksSaveWhenComponentHasNoFactoryPid', async () => {
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'ghost',
      factoryPid: '',
      inputPortCount: 1,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })

    await api().save()

    expect(mocks.putSnapshot).not.toHaveBeenCalled()
    expect(mocks.error).toHaveBeenCalledWith(expect.stringContaining('ghost'))
  })

  it('thenLoadsSnapshotIntoTheCanvas', async () => {
    mocks.getSnapshot.mockResolvedValue([
      {
        pid: GRAPH_SERVICE_PID,
        properties: {
          WireGraph: { type: 'STRING', value: JSON.stringify(graphOnly()) },
        },
      },
      timerConfig(),
    ])
    const store = useWiresGraphStore()

    await api().load()

    expect(store.components).toHaveLength(1)
    expect(store.components[0]?.factoryPid).toBe(TIMER_FACTORY_PID)
    expect(store.isDirty).toBe(false)
    expect(mocks.success).toHaveBeenCalled()
  })

  it('thenReportsInvalidSnapshotPayloadOnLoad', async () => {
    mocks.getSnapshot.mockResolvedValue([{ pid: GRAPH_SERVICE_PID, properties: {} }])
    const store = useWiresGraphStore()

    await api().load()

    expect(store.components).toHaveLength(0)
    expect(mocks.error).toHaveBeenCalled()
  })

  it('thenReportsLoadFailure', async () => {
    mocks.getSnapshot.mockRejectedValue(new Error('network down'))

    await api().load()

    expect(mocks.error).toHaveBeenCalledWith(expect.stringContaining('network down'))
  })

  it('thenRejectsSaveWhenTheGraphHasACycle', async () => {
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'a',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 1,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })
    store.addComponent({
      pid: 'b',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 1,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })
    // 绕过建线校验直接构造成环图（模拟导入的快照本身就成环）
    store.wires = [
      { emitterPid: 'a', emitterPort: 0, receiverPid: 'b', receiverPort: 0 },
      { emitterPid: 'b', emitterPort: 0, receiverPid: 'a', receiverPort: 0 },
    ]

    await api().save()

    expect(mocks.putSnapshot).not.toHaveBeenCalled()
    expect(mocks.error).toHaveBeenCalledWith(expect.stringContaining('环路'))
  })

  it('thenReportsSaveFailureWithBackendMessage', async () => {
    mocks.putSnapshot.mockRejectedValue(new Error('wires.messages.saveFailed:409'))
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'Timer',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })

    await api().save()

    expect(mocks.error).toHaveBeenCalled()
    expect(store.isDirty).toBe(true)
  })

  it('thenDeletesWholeGraph', async () => {
    mocks.deleteGraph.mockResolvedValue(undefined)
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'Timer',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })

    await api().deleteAll()

    expect(mocks.deleteGraph).toHaveBeenCalledTimes(1)
    expect(store.components).toHaveLength(0)
    expect(store.isDirty).toBe(false)
    expect(mocks.success).toHaveBeenCalled()
  })

  it('thenReportsDeleteFailure', async () => {
    mocks.deleteGraph.mockRejectedValue(new Error('409003'))
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'Timer',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })

    await api().deleteAll()

    expect(store.components).toHaveLength(1)
    expect(mocks.error).toHaveBeenCalled()
  })

  it('thenExportsSnapshotAsJsonDownload', () => {
    const createObjectURL = vi.fn(() => 'blob:wires')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.fn()
    const originalClick = HTMLAnchorElement.prototype.click
    HTMLAnchorElement.prototype.click = click
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'Timer',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })

    try {
      api().exportJson()
    } finally {
      HTMLAnchorElement.prototype.click = originalClick
    }

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:wires')
  })

  it('thenCreatesComponentsWithUniquePidsAndMetatypeDefaults', () => {
    const store = useWiresGraphStore()
    const descriptor = {
      factoryPid: TIMER_FACTORY_PID,
      name: 'Timer',
      minInputPorts: 0,
      maxInputPorts: 0,
      defaultInputPorts: 0,
      minOutputPorts: 1,
      maxOutputPorts: 1,
      defaultOutputPorts: 1,
      properties: [
        {
          id: 'type',
          name: 'type',
          type: 'STRING' as const,
          required: false,
          cardinality: 0,
          defaultValue: 'SIMPLE',
        },
      ],
    }
    const apiInstance = api()

    apiInstance.createComponent(descriptor, { x: 0, y: 0 })
    apiInstance.createComponent(descriptor, { x: 40, y: 40 })

    expect(store.components.map((component) => component.pid)).toEqual(['Timer', 'Timer2'])
    expect(store.components[0]?.properties).toEqual({ type: 'SIMPLE' })
    expect(store.components[0]?.inputPortCount).toBe(0)
    expect(store.components[1]?.outputPortCount).toBe(1)
  })

  it('thenReportsImportFailureForBrokenJson', async () => {
    const file = new File(['not json'], 'broken.json', { type: 'application/json' })

    await api().importJson(file)

    expect(mocks.error).toHaveBeenCalled()
  })

  it('thenSavesGraphWithFactoryPids', async () => {
    mocks.putSnapshot.mockResolvedValue(undefined)
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'Timer',
      factoryPid: TIMER_FACTORY_PID,
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })

    await api().save()

    expect(mocks.putSnapshot).toHaveBeenCalledTimes(1)
    const configs = mocks.putSnapshot.mock.calls[0]?.[0] as ComponentConfigurationDto[]
    expect(configs[0]?.pid).toBe(GRAPH_SERVICE_PID)
    expect(configs[1]?.properties[SERVICE_FACTORY_PID]?.value).toBe(TIMER_FACTORY_PID)
    expect(store.isDirty).toBe(false)
    expect(mocks.success).toHaveBeenCalled()
  })
})
