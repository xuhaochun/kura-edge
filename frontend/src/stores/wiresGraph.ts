/**
 * 图状态唯一来源（rules/05：禁止绕过 Pinia 直接跨组件改图状态）。
 * 对照上游 WireComposer + WiresPanelUi：组件列表、连线列表、dirty 标记、选中项。
 *
 * 快照契约（对照上游 WireGraphJsonMarshallUnmarshallImpl）：图 JSON 顶层只有
 * `components` / `wires`，组件项的 pid / inputPortCount / outputPortCount 同级，
 * 坐标与端口名在 renderingProperties 下；组件配置属性（含 service.factoryPid）
 * **不在图 JSON 里**，加载时由 {@link loadFromSnapshot} 的 configurationProperties 参数
 * （快照配置列表其余条目摊平而来）补充，保存时由 toSnapshotConfigurations 还原回独立条目。
 */
import { defineStore } from 'pinia'
import type { WireComponentState, WireConfiguration, WireGraphSnapshot } from '@/types/wires'
import { clampToGrid, type Point } from '@/composables/canvas'
import {
  graphPortCount,
  graphPosition,
  INPUT_PORT_COUNT,
  OUTPUT_PORT_COUNT,
  SERVICE_FACTORY_PID,
} from '@/api/wireGraphCodec'
import {
  isDuplicateWire,
  isWirePortValid,
  wouldCreateCycle,
} from '@/composables/useGraphValidation'
import { useMetadataStore } from '@/stores/metadata'

export interface AddComponentInput {
  pid: string
  factoryPid: string
  inputPortCount: number
  outputPortCount: number
  position: Point
  properties?: Record<string, unknown>
}

interface WiresGraphState {
  components: WireComponentState[]
  wires: WireConfiguration[]
  selectedPid: string | null
  dirty: boolean
}

export const useWiresGraphStore = defineStore('wiresGraph', {
  state: (): WiresGraphState => ({
    components: [],
    wires: [],
    selectedPid: null,
    dirty: false,
  }),

  getters: {
    isDirty: (state) => state.dirty,
    hasComponents: (state) => state.components.length > 0,
    selectedComponent: (state) =>
      state.components.find((component) => component.pid === state.selectedPid) ?? null,
    componentByPid: (state) => (pid: string) =>
      state.components.find((component) => component.pid === pid) ?? null,
  },

  actions: {
    /**
     * 从图 JSON + 组件配置属性重建整个图，并清空 dirty（对照上游 refreshWires 后的画布重建）。
     * 组件端口数/位置缺省时回落：端口数取组件定义的默认值，位置取原点（Kura 原生快照可省略）。
     *
     * @param graph 图 JSON（components / wires）
     * @param configurationProperties 组件配置属性表（来自快照配置列表其余条目，已摊平 {type,value}）
     */
    loadFromSnapshot(
      graph: WireGraphSnapshot,
      configurationProperties: Record<string, Record<string, unknown>> = {},
    ): void {
      const metadata = useMetadataStore()
      this.components = (graph.components ?? []).map((entry) => {
        const configuration = configurationProperties[entry.pid] ?? {}
        const rawFactoryPid = configuration[SERVICE_FACTORY_PID]
        const factoryPid = typeof rawFactoryPid === 'string' ? rawFactoryPid : ''
        const descriptor = metadata.descriptorByFactoryPid[factoryPid] ?? null
        const properties: Record<string, unknown> = {}
        for (const [key, value] of Object.entries(configuration)) {
          if (key !== SERVICE_FACTORY_PID) {
            properties[key] = value
          }
        }
        return {
          pid: entry.pid,
          factoryPid,
          inputPortCount: graphPortCount(
            entry,
            INPUT_PORT_COUNT,
            descriptor?.defaultInputPorts ?? 0,
          ),
          outputPortCount: graphPortCount(
            entry,
            OUTPUT_PORT_COUNT,
            descriptor?.defaultOutputPorts ?? 0,
          ),
          position: clampToGrid(graphPosition(entry, { x: 0, y: 0 })),
          properties,
        }
      })
      this.wires = (graph.wires ?? []).map((wire) => ({
        emitterPid: wire.emitter,
        emitterPort: wire.emitterPort,
        receiverPid: wire.receiver,
        receiverPort: wire.receiverPort,
      }))
      this.selectedPid = null
      this.dirty = false
    },

    /** 新建组件（PID 唯一性校验，对照上游 onNewPidInserted） */
    addComponent(input: AddComponentInput): void {
      if (this.components.some((component) => component.pid === input.pid)) {
        throw new Error('wires.validation.pidExists')
      }
      this.components.push({
        pid: input.pid,
        factoryPid: input.factoryPid,
        inputPortCount: input.inputPortCount,
        outputPortCount: input.outputPortCount,
        position: clampToGrid(input.position),
        properties: { ...(input.properties ?? {}) },
      })
      this.dirty = true
    },

    /** 删除组件并联动删除其所有连线（对照上游 deleteWireComponent + graph.remove） */
    removeComponent(pid: string): void {
      this.components = this.components.filter((component) => component.pid !== pid)
      this.wires = this.wires.filter((wire) => wire.emitterPid !== pid && wire.receiverPid !== pid)
      if (this.selectedPid === pid) {
        this.selectedPid = null
      }
      this.dirty = true
    },

    /**
     * 建线（端口校验 -> 重复校验 -> 环检测，与后端 WireGraphEngine 规则一致）。
     * 失败抛 Error(message=i18n key)，由 UI 层翻译提示。
     */
    addWire(wire: WireConfiguration): void {
      const emitter = this.componentByPid(wire.emitterPid)
      const receiver = this.componentByPid(wire.receiverPid)
      if (!emitter || !receiver) {
        throw new Error('wires.validation.componentMissing')
      }
      if (!isWirePortValid(wire, emitter.outputPortCount, receiver.inputPortCount)) {
        throw new Error('wires.validation.portRange')
      }
      if (isDuplicateWire(this.wires, wire)) {
        throw new Error('wires.validation.duplicate')
      }
      if (wouldCreateCycle(this.wires, wire)) {
        throw new Error('wires.validation.cycle')
      }
      this.wires.push({ ...wire })
      this.dirty = true
    },

    removeWire(wire: WireConfiguration): void {
      const before = this.wires.length
      this.wires = this.wires.filter(
        (candidate) =>
          !(
            candidate.emitterPid === wire.emitterPid &&
            candidate.emitterPort === wire.emitterPort &&
            candidate.receiverPid === wire.receiverPid &&
            candidate.receiverPort === wire.receiverPort
          ),
      )
      if (this.wires.length !== before) {
        this.dirty = true
      }
    },

    /** 拖拽结束回写位置（网格吸附，对照上游 fillRenderingProperties） */
    moveComponent(pid: string, position: Point): void {
      const component = this.componentByPid(pid)
      if (!component) {
        return
      }
      const clamped = clampToGrid(position)
      if (component.position.x !== clamped.x || component.position.y !== clamped.y) {
        component.position = clamped
        this.dirty = true
      }
    },

    updateProperties(pid: string, properties: Record<string, unknown>): void {
      const component = this.componentByPid(pid)
      if (!component) {
        return
      }
      component.properties = { ...properties }
      this.dirty = true
    },

    select(pid: string): void {
      this.selectedPid = pid
    },

    deselect(): void {
      this.selectedPid = null
    },

    /** 清空画布（对照上游 wireComposer.clear()） */
    clear(): void {
      this.components = []
      this.wires = []
      this.selectedPid = null
      this.dirty = true
    },

    /**
     * 序列化为图 JSON（顶层只有 components / wires，与上游 WireGraph JSON 一致）。
     * 组件配置属性不在其中，保存时由 api/wireGraphCodec#toSnapshotConfigurations 走独立条目。
     */
    toSnapshot(): WireGraphSnapshot {
      return {
        components: this.components.map((component) => ({
          pid: component.pid,
          inputPortCount: component.inputPortCount,
          outputPortCount: component.outputPortCount,
          renderingProperties: {
            position: { x: component.position.x, y: component.position.y },
          },
        })),
        wires: this.wires.map((wire) => ({
          emitter: wire.emitterPid,
          emitterPort: wire.emitterPort,
          receiver: wire.receiverPid,
          receiverPort: wire.receiverPort,
        })),
      }
    },

    markSaved(): void {
      this.dirty = false
    },

    /** 导入快照等仅本地生效的变更：标记未保存 */
    markDirty(): void {
      this.dirty = true
    },
  },
})
