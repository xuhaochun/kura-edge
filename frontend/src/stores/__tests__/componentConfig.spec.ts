/** componentConfig store 单测：表单载入、字段校验、应用回写（对照上游 ConfigurationAreaUi + ValidationData） */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useComponentConfigStore } from '@/stores/componentConfig'
import { useMetadataStore } from '@/stores/metadata'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import type { ComponentDescriptor, ConfigParameter } from '@/types/wires'

const LOGGER_FACTORY_PID = 'org.eclipse.kura.wires.Logger'

const parameter = (overrides: Partial<ConfigParameter>): ConfigParameter => ({
  id: 'level',
  name: 'Level',
  type: 'STRING',
  required: false,
  cardinality: 0,
  ...overrides,
})

const descriptor = (properties: ConfigParameter[]): ComponentDescriptor => ({
  factoryPid: LOGGER_FACTORY_PID,
  name: 'Logger',
  minInputPorts: 0,
  maxInputPorts: 1,
  defaultInputPorts: 1,
  minOutputPorts: 0,
  maxOutputPorts: 0,
  defaultOutputPorts: 0,
  properties,
})

function givenDescriptor(properties: ConfigParameter[]): void {
  useMetadataStore().descriptors = [descriptor(properties)]
}

function givenLoggerComponent(properties: Record<string, unknown> = {}): void {
  useWiresGraphStore().addComponent({
    pid: 'logger1',
    factoryPid: LOGGER_FACTORY_PID,
    inputPortCount: 1,
    outputPortCount: 0,
    position: { x: 0, y: 0 },
    properties,
  })
}

describe('useComponentConfigStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('begin', () => {
    it('thenFillsExistingPropertiesAndFieldDefaults', () => {
      givenDescriptor([
        parameter({ id: 'level', defaultValue: 'INFO' }),
        parameter({ id: 'topic', required: true }),
      ])
      givenLoggerComponent({ level: 'DEBUG' })
      const config = useComponentConfigStore()

      config.begin('logger1')

      expect(config.values.level).toBe('DEBUG')
      expect(config.values.topic).toBe('')
      expect(config.component?.pid).toBe('logger1')
      expect(config.parameters).toHaveLength(2)
    })

    it('thenClearsFormWhenSelectionIsRemoved', () => {
      givenDescriptor([parameter({})])
      givenLoggerComponent()
      const config = useComponentConfigStore()
      config.begin('logger1')

      config.begin(null)

      expect(config.component).toBeNull()
      expect(config.values).toEqual({})
      expect(config.results).toEqual({})
    })

    it('thenIgnoresUnknownPid', () => {
      givenDescriptor([parameter({})])
      const config = useComponentConfigStore()

      config.begin('missing')

      expect(config.component).toBeNull()
      expect(config.parameters).toEqual([])
    })
  })

  describe('validation', () => {
    it('thenReportsMissingRequiredField', () => {
      givenDescriptor([parameter({ id: 'topic', required: true })])
      givenLoggerComponent()
      const config = useComponentConfigStore()

      config.begin('logger1')

      expect(config.results.topic?.valid).toBe(false)
      expect(config.results.topic?.messageKey).toBe('wires.validation.required')
      expect(config.isValid).toBe(false)
    })

    it('thenReportsOutOfRangeNumericField', () => {
      givenDescriptor([parameter({ id: 'size', type: 'INTEGER', min: '1', max: '10' })])
      givenLoggerComponent()
      const config = useComponentConfigStore()
      config.begin('logger1')

      config.setValue('size', '99')

      expect(config.results.size?.valid).toBe(false)
      expect(config.results.size?.messageKey).toBe('wires.validation.max')
    })

    it('thenAcceptsValidForm', () => {
      givenDescriptor([parameter({ id: 'size', type: 'INTEGER', min: '1', max: '10' })])
      givenLoggerComponent()
      const config = useComponentConfigStore()
      config.begin('logger1')

      config.setValue('size', '5')

      expect(config.isValid).toBe(true)
    })
  })

  describe('apply', () => {
    it('thenWritesTypedPropertiesAndMarksGraphDirty', () => {
      givenDescriptor([
        parameter({ id: 'level' }),
        parameter({ id: 'size', type: 'INTEGER' }),
        parameter({ id: 'enabled', type: 'BOOLEAN' }),
      ])
      givenLoggerComponent()
      const graph = useWiresGraphStore()
      graph.markSaved()
      const config = useComponentConfigStore()
      config.begin('logger1')
      config.setValue('level', 'DEBUG')
      config.setValue('size', '5')
      config.setValue('enabled', 'true')

      const applied = config.apply()

      expect(applied).toBe(true)
      expect(graph.components[0]?.properties).toMatchObject({
        level: 'DEBUG',
        size: 5,
        enabled: true,
      })
      expect(graph.isDirty).toBe(true)
    })

    it('thenKeepsPropertiesNotCoveredByTheForm', () => {
      givenDescriptor([parameter({ id: 'level' })])
      givenLoggerComponent({ legacy: 'keep-me' })
      const config = useComponentConfigStore()
      config.begin('logger1')

      config.setValue('level', 'WARN')
      config.apply()

      expect(useWiresGraphStore().components[0]?.properties).toMatchObject({
        legacy: 'keep-me',
        level: 'WARN',
      })
    })

    it('thenRejectsApplyWhenValidationFails', () => {
      givenDescriptor([parameter({ id: 'topic', required: true })])
      givenLoggerComponent()
      const graph = useWiresGraphStore()
      const config = useComponentConfigStore()
      config.begin('logger1')

      const applied = config.apply()

      expect(applied).toBe(false)
      expect(graph.components[0]?.properties).toEqual({})
    })

    it('thenRejectsApplyWithoutSelection', () => {
      givenDescriptor([parameter({ id: 'level' })])
      const config = useComponentConfigStore()

      expect(config.apply()).toBe(false)
    })
  })

  describe('reset', () => {
    it('thenClearsEditingState', () => {
      givenDescriptor([parameter({})])
      givenLoggerComponent()
      const config = useComponentConfigStore()
      config.begin('logger1')

      config.reset()

      expect(config.editingPid).toBeNull()
      expect(config.values).toEqual({})
    })
  })
})
