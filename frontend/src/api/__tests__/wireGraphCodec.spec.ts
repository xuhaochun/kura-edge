/**
 * wireGraphCodec 单测：图 JSON（components / wires，上游线格式）⇄ REST 快照配置列表的编解码。
 * 对照上游 WireGraphJsonMarshallUnmarshallImpl 的 marshal/unmarshal 行为。
 */
import { describe, expect, it } from 'vitest'
import {
  extractGraphSnapshot,
  FACTORY_PID_MISSING,
  graphPortCount,
  graphPosition,
  parseGraphSnapshot,
  parseSnapshotFile,
  PASSWORD_PLACEHOLDER,
  SERVICE_FACTORY_PID,
  toSnapshotConfigurations,
  WIRE_GRAPH_PROPERTY,
  WIRE_GRAPH_SERVICE_PID,
} from '@/api/wireGraphCodec'
import type {
  ComponentConfigurationDto,
  ComponentDescriptor,
  ConfigParameter,
  WireComponentState,
  WireGraphSnapshot,
} from '@/types/wires'

const TIMER_FACTORY_PID = 'org.eclipse.kura.wire.Timer'
const LOGGER_FACTORY_PID = 'org.eclipse.kura.wire.Logger'

/** 真实上游风格的图 JSON（WireGraph 属性的内容）：多端口 + renderingProperties + 一条连线 */
const UPSTREAM_GRAPH_JSON = `{
  "components": [
    {
      "pid": "timer1",
      "inputPortCount": 0,
      "outputPortCount": 1,
      "renderingProperties": {
        "position": { "x": 40, "y": 60 },
        "inputPortNames": {},
        "outputPortNames": { "0": "out" }
      }
    },
    {
      "pid": "logger1",
      "inputPortCount": 2,
      "outputPortCount": 0,
      "renderingProperties": {
        "position": { "x": 200, "y": 60 },
        "inputPortNames": { "0": "in0", "1": "in1" },
        "outputPortNames": {}
      }
    }
  ],
  "wires": [{ "emitter": "timer1", "emitterPort": 0, "receiver": "logger1", "receiverPort": 1 }]
}`

const graph: WireGraphSnapshot = {
  components: [
    {
      pid: 'timer1',
      inputPortCount: 0,
      outputPortCount: 1,
      renderingProperties: { position: { x: 40, y: 60 }, outputPortNames: { '0': 'out' } },
    },
    {
      pid: 'logger1',
      inputPortCount: 1,
      outputPortCount: 0,
      renderingProperties: { position: { x: 200, y: 60 } },
    },
  ],
  wires: [{ emitter: 'timer1', emitterPort: 0, receiver: 'logger1', receiverPort: 0 }],
}

const components: WireComponentState[] = [
  {
    pid: 'timer1',
    factoryPid: TIMER_FACTORY_PID,
    inputPortCount: 0,
    outputPortCount: 1,
    position: { x: 40, y: 60 },
    properties: { 'simple.interval': 10 },
  },
  {
    pid: 'logger1',
    factoryPid: LOGGER_FACTORY_PID,
    inputPortCount: 1,
    outputPortCount: 0,
    position: { x: 200, y: 60 },
    properties: { topic: 't', password: PASSWORD_PLACEHOLDER },
  },
]

const parameter = (overrides: Partial<ConfigParameter>): ConfigParameter => ({
  id: 'field',
  name: 'Field',
  type: 'STRING',
  required: false,
  cardinality: 0,
  ...overrides,
})

const descriptor = (factoryPid: string, properties: ConfigParameter[]): ComponentDescriptor => ({
  factoryPid,
  name: factoryPid,
  minInputPorts: 0,
  maxInputPorts: 1,
  defaultInputPorts: 1,
  minOutputPorts: 0,
  maxOutputPorts: 1,
  defaultOutputPorts: 1,
  properties,
})

const descriptors: Record<string, ComponentDescriptor> = {
  [TIMER_FACTORY_PID]: descriptor(TIMER_FACTORY_PID, [
    parameter({ id: 'simple.interval', type: 'INTEGER' }),
  ]),
  [LOGGER_FACTORY_PID]: descriptor(LOGGER_FACTORY_PID, [
    parameter({ id: 'password', type: 'PASSWORD' }),
  ]),
}

/** 上游 /graph/snapshot 形态：第一条 WireGraphService 配置，其余为组件配置 */
const configList: ComponentConfigurationDto[] = [
  {
    pid: WIRE_GRAPH_SERVICE_PID,
    properties: { [WIRE_GRAPH_PROPERTY]: { type: 'STRING', value: UPSTREAM_GRAPH_JSON } },
  },
  {
    pid: 'timer1',
    properties: {
      [SERVICE_FACTORY_PID]: { type: 'STRING', value: TIMER_FACTORY_PID },
      'simple.interval': { type: 'INTEGER', value: 10 },
    },
  },
  {
    pid: 'logger1',
    properties: {
      [SERVICE_FACTORY_PID]: { type: 'STRING', value: LOGGER_FACTORY_PID },
      topic: { type: 'STRING', value: 't' },
      password: { type: 'PASSWORD', value: PASSWORD_PLACEHOLDER },
    },
  },
]

function graphJsonOf(configs: ComponentConfigurationDto[]): Record<string, unknown> {
  const value = configs[0]?.properties[WIRE_GRAPH_PROPERTY]?.value
  return JSON.parse(String(value)) as Record<string, unknown>
}

describe('wireGraphCodec', () => {
  describe('parseGraphSnapshot', () => {
    it('thenParsesAnUpstreamStyleGraphJson', () => {
      const result = parseGraphSnapshot(JSON.parse(UPSTREAM_GRAPH_JSON))

      expect(result?.components.map((component) => component.pid)).toEqual(['timer1', 'logger1'])
      expect(result?.components[0]?.inputPortCount).toBe(0)
      expect(result?.components[0]?.outputPortCount).toBe(1)
      expect(result?.components[1]?.inputPortCount).toBe(2)
      expect(result?.components[1]?.renderingProperties?.position).toEqual({ x: 200, y: 60 })
      expect(result?.components[1]?.renderingProperties?.inputPortNames).toEqual({
        '0': 'in0',
        '1': 'in1',
      })
      expect(result?.components[0]?.renderingProperties?.outputPortNames).toEqual({ '0': 'out' })
      expect(result?.wires).toEqual([
        { emitter: 'timer1', emitterPort: 0, receiver: 'logger1', receiverPort: 1 },
      ])
    })

    it('thenAppliesCallerFallbacksWhenFieldsAreMissing', () => {
      const result = parseGraphSnapshot({ components: [{ pid: 'solo' }] })
      const component = result!.components[0]!

      expect(component.renderingProperties).toBeUndefined()
      expect(graphPortCount(component, 'inputPortCount', 3)).toBe(3)
      expect(graphPortCount(component, 'outputPortCount', 4)).toBe(4)
      expect(graphPosition(component, { x: 5, y: 6 })).toEqual({ x: 5, y: 6 })
      expect(result?.wires).toEqual([])
    })

    it('thenToleratesPartialAndNonNumericRenderingFields', () => {
      const result = parseGraphSnapshot({
        components: [
          {
            pid: 'a',
            inputPortCount: 'many',
            outputPortCount: 2,
            renderingProperties: { position: { x: 10 } },
          },
        ],
      })
      const component = result!.components[0]!

      expect(component.inputPortCount).toBeUndefined()
      expect(graphPortCount(component, 'inputPortCount', 0)).toBe(0)
      expect(component.outputPortCount).toBe(2)
      // 解析层把缺失的坐标轴补 0（store 的位置缺省同样是原点）
      expect(graphPosition(component, { x: 1, y: 2 })).toEqual({ x: 10, y: 0 })
    })

    it('thenSkipsComponentsWithoutPidAndWiresWithoutEmitterOrReceiver', () => {
      const result = parseGraphSnapshot({
        components: [{}, { pid: 4 }, { pid: 'timer1', outputPortCount: 1 }],
        wires: [
          { emitter: 'timer1', receiver: 'logger1' },
          { emitter: 'timer1', emitterPort: 0 },
          { emitterPort: 0, receiverPort: 0 },
          'nope',
        ],
      })

      expect(result?.components.map((component) => component.pid)).toEqual(['timer1'])
      expect(result?.wires).toEqual([
        { emitter: 'timer1', emitterPort: 0, receiver: 'logger1', receiverPort: 0 },
      ])
    })

    it('thenToleratesCaseInsensitiveTopLevelKeys', () => {
      const parsed = parseGraphSnapshot({
        Components: [{ pid: 'timer1', outputPortCount: 1 }],
        WIRES: [],
      })

      expect(parsed?.components).toHaveLength(1)
      expect(parsed?.components[0]?.pid).toBe('timer1')
      expect(parsed?.wires).toEqual([])
    })

    it('thenRejectsValuesWithoutAComponentsArray', () => {
      expect(parseGraphSnapshot(null)).toBeNull()
      expect(parseGraphSnapshot('nope')).toBeNull()
      expect(parseGraphSnapshot([])).toBeNull()
      expect(parseGraphSnapshot({ wires: [] })).toBeNull()
      expect(parseGraphSnapshot({ components: {} })).toBeNull()
      expect(parseGraphSnapshot({ components: [], wires: 'nope' })).toBeNull()
    })
  })

  describe('graphPortCount / graphPosition', () => {
    it('thenReadsTheCountingAndPositionFromTheComponentItem', () => {
      const parsed = parseGraphSnapshot(JSON.parse(UPSTREAM_GRAPH_JSON))

      expect(graphPortCount(parsed!.components[0]!, 'outputPortCount', 9)).toBe(1)
      expect(graphPortCount(parsed!.components[0]!, 'inputPortCount', 9)).toBe(0)
      expect(graphPosition(parsed!.components[0]!, { x: 0, y: 0 })).toEqual({ x: 40, y: 60 })
    })

    it('thenFallsBackWhenRenderingPropertiesAreAbsent', () => {
      const component = parseGraphSnapshot({ components: [{ pid: 'solo' }] })!.components[0]!

      expect(graphPosition(component, { x: 80, y: 100 })).toEqual({ x: 80, y: 100 })
    })
  })

  describe('extractGraphSnapshot', () => {
    it('thenRestoresTopologyAndComponentProperties', () => {
      const extracted = extractGraphSnapshot(configList)

      expect(extracted?.graph.components).toHaveLength(2)
      expect(extracted?.graph.wires).toHaveLength(1)
      expect(extracted?.configurationProperties.timer1).toEqual({
        [SERVICE_FACTORY_PID]: TIMER_FACTORY_PID,
        'simple.interval': 10,
      })
      expect(extracted?.configurationProperties.logger1).toEqual({
        [SERVICE_FACTORY_PID]: LOGGER_FACTORY_PID,
        topic: 't',
        password: PASSWORD_PLACEHOLDER,
      })
      expect(extracted?.configurationProperties[WIRE_GRAPH_SERVICE_PID]).toBeUndefined()
    })

    it('thenReturnsNullWithoutTheGraphServiceEntry', () => {
      expect(extractGraphSnapshot([{ pid: 'timer1', properties: {} }])).toBeNull()
      expect(extractGraphSnapshot(null)).toBeNull()
      expect(
        extractGraphSnapshot([
          { pid: WIRE_GRAPH_SERVICE_PID, properties: { topic: { type: 'STRING', value: 'x' } } },
        ]),
      ).toBeNull()
    })

    it('thenReturnsNullWhenTheGraphPropertyIsNotAString', () => {
      const configs: ComponentConfigurationDto[] = [
        {
          pid: WIRE_GRAPH_SERVICE_PID,
          properties: { [WIRE_GRAPH_PROPERTY]: { type: 'INTEGER', value: 3 } },
        },
      ]

      expect(extractGraphSnapshot(configs)).toBeNull()
    })

    it('thenReturnsNullOnMalformedOrForeignGraphJson', () => {
      const malformed: ComponentConfigurationDto[] = [
        {
          pid: WIRE_GRAPH_SERVICE_PID,
          properties: { [WIRE_GRAPH_PROPERTY]: { type: 'STRING', value: '{not json' } },
        },
      ]
      const foreign: ComponentConfigurationDto[] = [
        {
          pid: WIRE_GRAPH_SERVICE_PID,
          properties: {
            [WIRE_GRAPH_PROPERTY]: {
              type: 'STRING',
              value: JSON.stringify({ wireComponentConfigurations: [], wireConfigurations: [] }),
            },
          },
        },
      ]

      expect(extractGraphSnapshot(malformed)).toBeNull()
      expect(extractGraphSnapshot(foreign)).toBeNull()
    })
  })

  describe('toSnapshotConfigurations', () => {
    it('thenEmitsTheGraphServiceEntryFirst', () => {
      const configs = toSnapshotConfigurations(graph, components, descriptors)

      expect(configs[0]?.pid).toBe(WIRE_GRAPH_SERVICE_PID)
      expect(configs[0]?.properties[WIRE_GRAPH_PROPERTY]?.type).toBe('STRING')
      expect(configs.slice(1).map((config) => config.pid)).toEqual(['timer1', 'logger1'])
    })

    it('thenMarshalsOnlyComponentsAndWires', () => {
      const json = graphJsonOf(toSnapshotConfigurations(graph, components, descriptors))
      const serialized = JSON.stringify(json)

      expect(Object.keys(json)).toEqual(['components', 'wires'])
      expect(json).toEqual(graph)
      expect(serialized).not.toContain('wireComponentConfigurations')
      expect(serialized).not.toContain('wireConfigurations')
      expect(serialized).not.toContain(SERVICE_FACTORY_PID)
      expect(serialized).not.toContain('simple.interval')
      expect(serialized).not.toContain('topic')
      expect(serialized).not.toContain(PASSWORD_PLACEHOLDER)
    })

    it('thenTypesDeclaredPropertiesFromMetadata', () => {
      const configs = toSnapshotConfigurations(graph, components, descriptors)

      expect(configs.find((config) => config.pid === 'logger1')?.properties.password).toEqual({
        type: 'PASSWORD',
        value: PASSWORD_PLACEHOLDER,
      })
      expect(
        configs.find((config) => config.pid === 'timer1')?.properties['simple.interval'],
      ).toEqual({
        type: 'INTEGER',
        value: 10,
      })
    })

    it('thenAlwaysSendsStringFactoryPid', () => {
      const configs = toSnapshotConfigurations(graph, components, descriptors)

      expect(
        configs.find((config) => config.pid === 'timer1')?.properties[SERVICE_FACTORY_PID],
      ).toEqual({ type: 'STRING', value: TIMER_FACTORY_PID })
    })

    it('thenInfersTypesWithoutMetadata', () => {
      const configs = toSnapshotConfigurations(graph, components)

      expect(
        configs.find((config) => config.pid === 'timer1')?.properties['simple.interval']?.type,
      ).toBe('INTEGER')
      expect(configs.find((config) => config.pid === 'logger1')?.properties.password?.type).toBe(
        'STRING',
      )
    })

    it('thenRejectsAComponentWithoutFactoryPid', () => {
      const orphan: WireComponentState = {
        pid: 'orphan',
        factoryPid: '',
        inputPortCount: 0,
        outputPortCount: 0,
        position: { x: 0, y: 0 },
        properties: {},
      }

      expect(() => toSnapshotConfigurations(graph, [orphan])).toThrow(FACTORY_PID_MISSING)
      expect(FACTORY_PID_MISSING).toBe('wires.validation.factoryPidMissing')
    })

    it('thenRoundTripsThroughTheSnapshotList', () => {
      const extracted = extractGraphSnapshot(
        toSnapshotConfigurations(graph, components, descriptors),
      )

      expect(extracted?.graph).toEqual(graph)
      expect(extracted?.configurationProperties).toEqual({
        timer1: { [SERVICE_FACTORY_PID]: TIMER_FACTORY_PID, 'simple.interval': 10 },
        logger1: {
          [SERVICE_FACTORY_PID]: LOGGER_FACTORY_PID,
          topic: 't',
          password: PASSWORD_PLACEHOLDER,
        },
      })
    })
  })

  describe('parseSnapshotFile', () => {
    it('thenAcceptsAGraphJson', () => {
      const extracted = parseSnapshotFile(JSON.parse(UPSTREAM_GRAPH_JSON))

      expect(extracted?.graph.components).toHaveLength(2)
      expect(extracted?.graph.wires).toHaveLength(1)
      expect(extracted?.configurationProperties).toEqual({})
    })

    it('thenAcceptsASnapshotConfigList', () => {
      const extracted = parseSnapshotFile({ configs: configList })

      expect(extracted?.graph.components).toHaveLength(2)
      expect(extracted?.configurationProperties.timer1?.topic).toBeUndefined()
      expect(extracted?.configurationProperties.logger1?.topic).toBe('t')
    })

    it('thenRejectsForeignPayloads', () => {
      expect(parseSnapshotFile('nope')).toBeNull()
      expect(parseSnapshotFile(null)).toBeNull()
      expect(parseSnapshotFile({ foo: 1 })).toBeNull()
      expect(parseSnapshotFile({ configs: 'nope' })).toBeNull()
      expect(
        parseSnapshotFile({ wireComponentConfigurations: [], wireConfigurations: [] }),
      ).toBeNull()
    })
  })
})
