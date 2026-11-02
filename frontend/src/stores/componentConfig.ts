/**
 * 配置区表单状态（对照上游 ConfigurationAreaUi + ValidationData）。
 *
 * 只持有「编辑中」的派生状态（当前 pid、字段的字符串表单值、校验结果）；
 * 图本身（组件属性、端口、位置）仍在 wiresGraph store —— 本 store 通过
 * component 派生 getter 读取，应用时经 wiresGraph.updateProperties 回写，
 * 不允许组件绕过 Pinia 直接改图状态（rules/05）。
 */
import { defineStore } from 'pinia'
import { useMetadataStore } from '@/stores/metadata'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import {
  toPropertyScalar,
  validateConfiguration,
  type ParameterValidationResult,
} from '@/composables/useGraphValidation'
import type { ConfigParameter, WireComponentState } from '@/types/wires'

interface ComponentConfigState {
  /** 正在编辑的组件 pid，null 表示未选中 */
  editingPid: string | null
  /** 字段 id -> 字符串表单值（与上游 metatype 渲染一致，一律以字符串承载） */
  values: Record<string, string>
}

export const useComponentConfigStore = defineStore('componentConfig', {
  state: (): ComponentConfigState => ({
    editingPid: null,
    values: {},
  }),

  getters: {
    /** 当前编辑的组件（来自图 store，保持单一数据源） */
    component(state): WireComponentState | null {
      if (state.editingPid === null) {
        return null
      }
      return useWiresGraphStore().componentByPid(state.editingPid)
    },

    /** 当前组件的字段定义（来自 GET /metadata 的 properties） */
    parameters(): ConfigParameter[] {
      const component = this.component
      if (!component) {
        return []
      }
      const descriptor = useMetadataStore().descriptorByFactoryPid[component.factoryPid]
      return descriptor?.properties ?? []
    },

    /** 每个字段的校验结果（对照上游 ValidationData） */
    results(): Record<string, ParameterValidationResult> {
      return validateConfiguration(this.parameters, this.values)
    },

    /** 整表是否通过校验 */
    isValid(): boolean {
      return this.parameters.every((param) => this.results[param.id]?.valid === true)
    },
  },

  actions: {
    /**
     * 切换编辑对象：清空表单并按「组件现有属性 > 字段默认值」重填
     * （对照上游 ConfigurationAreaUi 切换组件时的重填行为）。
     *
     * @param pid 组件 pid，null 表示取消选中
     */
    begin(pid: string | null): void {
      this.editingPid = pid
      this.values = {}
      if (pid === null) {
        return
      }
      const component = this.component
      if (!component) {
        return
      }
      const descriptor = useMetadataStore().descriptorByFactoryPid[component.factoryPid]
      for (const param of descriptor?.properties ?? []) {
        const existing = component.properties[param.id]
        this.values[param.id] =
          existing === undefined || existing === null
            ? (param.defaultValue ?? '')
            : String(existing)
      }
    },

    /** 更新单个字段的表单值（输入控件 v-model 入口） */
    setValue(id: string, value: string): void {
      this.values[id] = value
    },

    /** 按字段类型把表单值转换为配置属性（标量），未覆盖的既有属性原样保留 */
    toProperties(): Record<string, unknown> {
      const component = this.component
      const properties: Record<string, unknown> = { ...(component?.properties ?? {}) }
      for (const param of this.parameters) {
        properties[param.id] = toPropertyScalar(param, this.values[param.id])
      }
      return properties
    },

    /**
     * 应用表单：校验通过则回写图 store 的组件属性并返回 true，否则返回 false
     * （对照上游「校验失败阻止 apply」）。
     *
     * @return 是否已应用
     */
    apply(): boolean {
      const component = this.component
      if (!component || !this.isValid) {
        return false
      }
      useWiresGraphStore().updateProperties(component.pid, this.toProperties())
      return true
    },

    /** 清空表单状态（离开页面/删除组件时调用） */
    reset(): void {
      this.editingPid = null
      this.values = {}
    },
  },
})
