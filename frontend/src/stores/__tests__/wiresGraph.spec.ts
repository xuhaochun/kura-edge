/** wiresGraph store 单测：增删组件/连线、dirty 标记、图 JSON（components/wires）序列化与重建 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWiresGraphStore, type AddComponentInput } from '@/stores/wiresGraph'
import { useMetadataStore } from '@/stores/metadata'
import { PASSWORD_PLACEHOLDER, SERVICE_FACTORY_PID } from '@/api/wireGraphCodec'
import type { ComponentDescriptor, ConfigParameter, WireGraphSnapshot } from '@/types/wires'

const TIMER_FACTORY_PID = 'org.eclipse.kura.wire.Timer'
const LOGGER_FACTORY_PID = 'org.eclipse.kura.wire.Logger'

const input = (overrides: Partial<AddComponentInput> = {}): AddComponentInput => ({
  pid: 'timer1',
  factoryPid: TIMER_FACTORY_PID,
  inputPortCount: 0,
  outputPortCount: 1,
  position: { x: 45, y: 60 },
  ...overrides,
})

const descriptor = (overrides: Partial<ComponentDescriptor> = {}): ComponentDescriptor => ({
  factoryPid: TIMER_FACTORY_PID,
  name: 'Timer',
  minInputPorts: 0,
  maxInputPorts: 1,
  defaultInputPorts: 0,
  minOutputPorts: 0,
  maxOutputPorts: 1,
  defaultOutputPorts: 1,
  properties: [] as ConfigParameter[],
  ...overrides,
})

/** 上游线格式的图 JSON：组件项 pid/端口数同级 + renderingProperties.position，连线用 emitter/receiver */
const graph: WireGraphSnapshot = {
  components: [
    {
      pid: 'logger1',
      inputPortCount: 1,
      outputPortCount: 0,
      renderingProperties: { position: { x: 20, y: 40 }, inputPortNames: { '0': 'in' } },
    },
    {
      pid: 'timer1',
      inputPortCount: 0,
      outputPortCount: 1,
      renderingProperties: { position: { x: 200, y: 80 }, outputPortNames: { '0': 'out' } },
    },
  ],
  wires: [{ emitter: 'timer1', emitterPort: 0, receiver: 'logger1', receiverPort: 0 }],
}

/** 组件配置属性表：快照配置列表其余条目摊平而来（含 service.factoryPid 与密码占位符） */
const configurationProperties: Record<string, Record<string, unknown>> = {
  logger1: {
    [SERVICE_FACTORY_PID]: LOGGER_FACTORY_PID,
    topic: 't',
    password: PASSWORD_PLACEHOLDER,
  },
  timer1: { [SERVICE_FACTORY_PID]: TIMER_FACTORY_PID, 'simple.interval': 10 },
}

describe('useWiresGraphStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('addComponent', () => {
    it('thenAddsComponentAndMarksDirty', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      expect(store.components).toHaveLength(1)
      expect(store.isDirty).toBe(true)
    })

    it('thenClampsPositionToGrid', () => {
      const store = useWiresGraphStore()
      store.addComponent(input({ position: { x: 45, y: 60 } }))
      expect(store.components[0]?.position).toEqual({ x: 40, y: 60 })
    })

    it('thenRejectsDuplicatePid', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      expect(() => store.addComponent(input({ pid: 'timer1' }))).toThrow(
        'wires.validation.pidExists',
      )
    })
  })

  describe('removeComponent', () => {
    it('thenCascadesWireDeletion', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.addComponent(input({ pid: 'logger1', inputPortCount: 1, outputPortCount: 0 }))
      store.addWire({
        emitterPid: 'timer1',
        emitterPort: 0,
        receiverPid: 'logger1',
        receiverPort: 0,
      })
      store.removeComponent('timer1')
      expect(store.components).toHaveLength(1)
      expect(store.wires).toHaveLength(0)
    })

    it('thenClearsSelectionOfRemovedComponent', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.select('timer1')
      store.removeComponent('timer1')
      expect(store.selectedPid).toBeNull()
    })
  })

  describe('addWire', () => {
    it('thenAddsWireAndMarksDirty', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.addComponent(input({ pid: 'logger1', inputPortCount: 1, outputPortCount: 0 }))
      store.addWire({
        emitterPid: 'timer1',
        emitterPort: 0,
        receiverPid: 'logger1',
        receiverPort: 0,
      })
      expect(store.wires).toHaveLength(1)
      expect(store.isDirty).toBe(true)
    })

    it('thenRejectsDuplicateWire', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.addComponent(input({ pid: 'logger1', inputPortCount: 1, outputPortCount: 0 }))
      const wire = { emitterPid: 'timer1', emitterPort: 0, receiverPid: 'logger1', receiverPort: 0 }
      store.addWire(wire)
      expect(() => store.addWire(wire)).toThrow('wires.validation.duplicate')
    })

    it('thenRejectsOutOfRangePort', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.addComponent(input({ pid: 'logger1', inputPortCount: 1, outputPortCount: 0 }))
      expect(() =>
        store.addWire({
          emitterPid: 'timer1',
          emitterPort: 1,
          receiverPid: 'logger1',
          receiverPort: 0,
        }),
      ).toThrow('wires.validation.portRange')
    })

    it('thenRejectsCycle', () => {
      const store = useWiresGraphStore()
      store.addComponent(input({ pid: 'a', outputPortCount: 1, inputPortCount: 1 }))
      store.addComponent(input({ pid: 'b', outputPortCount: 1, inputPortCount: 1 }))
      store.addWire({ emitterPid: 'a', emitterPort: 0, receiverPid: 'b', receiverPort: 0 })
      expect(() =>
        store.addWire({ emitterPid: 'b', emitterPort: 0, receiverPid: 'a', receiverPort: 0 }),
      ).toThrow('wires.validation.cycle')
    })
  })

  describe('moveComponent / updateProperties', () => {
    it('thenMarksDirtyOnlyOnRealChange', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.markSaved()
      store.moveComponent('timer1', { x: 100, y: 120 })
      expect(store.components[0]?.position).toEqual({ x: 100, y: 120 })
      expect(store.isDirty).toBe(true)
      store.markSaved()
      store.moveComponent('timer1', { x: 100, y: 120 })
      expect(store.isDirty).toBe(false)
    })

    it('thenUpdatePropertiesMarksDirty', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.markSaved()
      store.updateProperties('timer1', { cron: '* * * * *' })
      expect(store.components[0]?.properties).toEqual({ cron: '* * * * *' })
      expect(store.isDirty).toBe(true)
    })
  })

  describe('loadFromSnapshot', () => {
    it('thenRebuildsComponentsAndWiresFromTheGraphJson', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.select('timer1')

      store.loadFromSnapshot(graph, configurationProperties)

      expect(store.components.map((component) => component.pid)).toEqual(['logger1', 'timer1'])
      expect(store.components[1]).toEqual({
        pid: 'timer1',
        factoryPid: TIMER_FACTORY_PID,
        inputPortCount: 0,
        outputPortCount: 1,
        position: { x: 200, y: 80 },
        properties: { 'simple.interval': 10 },
      })
      expect(store.components[0]?.position).toEqual({ x: 20, y: 40 })
      expect(store.wires).toEqual([
        { emitterPid: 'timer1', emitterPort: 0, receiverPid: 'logger1', receiverPort: 0 },
      ])
      expect(store.selectedPid).toBeNull()
      expect(store.isDirty).toBe(false)
    })

    it('thenKeepsThePasswordPlaceholderAndDropsFactoryPidFromProperties', () => {
      const store = useWiresGraphStore()

      store.loadFromSnapshot(graph, configurationProperties)

      expect(store.components[0]?.properties).toEqual({
        topic: 't',
        password: PASSWORD_PLACEHOLDER,
      })
    })

    it('thenFallsBackToDescriptorDefaultsAndOriginWhenGraphFieldsAreMissing', () => {
      useMetadataStore().descriptors = [descriptor({ defaultInputPorts: 2, defaultOutputPorts: 3 })]
      const store = useWiresGraphStore()

      store.loadFromSnapshot(
        { components: [{ pid: 'timer1' }], wires: [] },
        { timer1: { [SERVICE_FACTORY_PID]: TIMER_FACTORY_PID } },
      )

      expect(store.components[0]?.inputPortCount).toBe(2)
      expect(store.components[0]?.outputPortCount).toBe(3)
      expect(store.components[0]?.position).toEqual({ x: 0, y: 0 })
      expect(store.components[0]?.properties).toEqual({})
    })

    it('thenToleratesMissingConfigurationProperties', () => {
      const store = useWiresGraphStore()

      store.loadFromSnapshot({ components: [{ pid: 'timer1' }], wires: [] })

      expect(store.components[0]?.factoryPid).toBe('')
      expect(store.components[0]?.properties).toEqual({})
      expect(store.isDirty).toBe(false)
    })
  })

  describe('toSnapshot', () => {
    it('thenEmitsComponentsAndWiresInTheUpstreamShape', () => {
      const store = useWiresGraphStore()
      store.loadFromSnapshot(graph, configurationProperties)

      const result = store.toSnapshot()

      expect(Object.keys(result)).toEqual(['components', 'wires'])
      expect(result.components).toEqual([
        {
          pid: 'logger1',
          inputPortCount: 1,
          outputPortCount: 0,
          renderingProperties: { position: { x: 20, y: 40 } },
        },
        {
          pid: 'timer1',
          inputPortCount: 0,
          outputPortCount: 1,
          renderingProperties: { position: { x: 200, y: 80 } },
        },
      ])
      expect(result.wires).toEqual([
        { emitter: 'timer1', emitterPort: 0, receiver: 'logger1', receiverPort: 0 },
      ])
    })

    it('thenOmitsComponentConfigurationAndDeprecatedTopLevelKeys', () => {
      const store = useWiresGraphStore()
      store.loadFromSnapshot(graph, configurationProperties)

      const serialized = JSON.stringify(store.toSnapshot())

      expect(serialized).not.toContain(SERVICE_FACTORY_PID)
      expect(serialized).not.toContain('simple.interval')
      expect(serialized).not.toContain('topic')
      expect(serialized).not.toContain(PASSWORD_PLACEHOLDER)
      expect(serialized).not.toContain('wireComponentConfigurations')
      expect(serialized).not.toContain('wireConfigurations')
      expect(serialized).not.toContain('emitterPid')
      expect(serialized).not.toContain('receiverPid')
    })

    it('thenSerializesTheCurrentCanvasEvenWhenNeverLoadedFromASnapshot', () => {
      const store = useWiresGraphStore()
      store.addComponent(input())
      store.addComponent(input({ pid: 'logger1', inputPortCount: 1, outputPortCount: 0 }))
      store.addWire({
        emitterPid: 'timer1',
        emitterPort: 0,
        receiverPid: 'logger1',
        receiverPort: 0,
      })

      const result = store.toSnapshot()

      expect(result.components[0]).toEqual({
        pid: 'timer1',
        inputPortCount: 0,
        outputPortCount: 1,
        renderingProperties: { position: { x: 40, y: 60 } },
      })
      expect(result.wires).toEqual([
        { emitter: 'timer1', emitterPort: 0, receiver: 'logger1', receiverPort: 0 },
      ])
    })

    it('thenRoundTripsThroughLoadFromSnapshot', () => {
      const store = useWiresGraphStore()
      store.loadFromSnapshot(graph, configurationProperties)
      const components = JSON.parse(JSON.stringify(store.components)) as unknown
      const wires = JSON.parse(JSON.stringify(store.wires)) as unknown

      store.loadFromSnapshot(store.toSnapshot(), configurationProperties)

      expect(store.components).toEqual(components)
      expect(store.wires).toEqual(wires)
    })
  })

  describe('clear / markDirty', () => {
    it('thenClearResetsGraphAndMarksDirty', () => {
      const store = useWiresGraphStore()
      store.loadFromSnapshot(graph, configurationProperties)
      store.clear()
      expect(store.components).toHaveLength(0)
      expect(store.wires).toHaveLength(0)
      expect(store.isDirty).toBe(true)
      store.markSaved()
      store.markDirty()
      expect(store.isDirty).toBe(true)
    })
  })
})
