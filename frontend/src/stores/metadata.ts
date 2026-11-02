/** 组件元数据（GET /metadata），面板与配置表单的数据源 */
import { defineStore } from 'pinia'
import type {
  Ad,
  ComponentDescriptor,
  ConfigParameter,
  WireComponentDefinition,
  WiresMetadata,
} from '@/types/wires'
import { getMetadata } from '@/api/metadata'

interface MetadataState {
  descriptors: ComponentDescriptor[]
  loaded: boolean
  loading: boolean
}

/**
 * metatype AD → 表单字段：表单值一律以字符串承载，故 min/max/defaultValue 统一转字符串；
 * options 转成 {value: label} 便于下拉渲染。
 */
export function toConfigParameter(ad: Ad): ConfigParameter {
  const options: Record<string, string> = {}
  for (const option of ad.options ?? []) {
    options[option.value] = option.label
  }
  return {
    id: ad.id,
    name: ad.name,
    description: ad.description ?? null,
    type: ad.type,
    required: ad.required ?? false,
    defaultValue:
      ad.defaultValue === undefined || ad.defaultValue === null ? null : String(ad.defaultValue),
    cardinality: 0,
    options: Object.keys(options).length > 0 ? options : null,
    min: ad.min === undefined || ad.min === null ? null : String(ad.min),
    max: ad.max === undefined || ad.max === null ? null : String(ad.max),
  }
}

/**
 * 组件定义（上游 DTO）→ 画布/表单用的描述符：端口上下限在本项目取工厂默认值，
 * 标签由 factoryPid 末段推导（上游 DTO 不含 display name）。
 */
export function toComponentDescriptor(definition: WireComponentDefinition): ComponentDescriptor {
  const segments = definition.factoryPid.split('.')
  return {
    factoryPid: definition.factoryPid,
    name: segments[segments.length - 1] ?? definition.factoryPid,
    minInputPorts: definition.minInputPorts,
    maxInputPorts: definition.maxInputPorts,
    defaultInputPorts: definition.defaultInputPorts,
    minOutputPorts: definition.minOutputPorts,
    maxOutputPorts: definition.maxOutputPorts,
    defaultOutputPorts: definition.defaultOutputPorts,
    inputPortNames: definition.inputPortNames ?? null,
    outputPortNames: definition.outputPortNames ?? null,
    properties: (definition.componentOcd ?? []).map(toConfigParameter),
  }
}

export const useMetadataStore = defineStore('metadata', {
  state: (): MetadataState => ({
    descriptors: [],
    loaded: false,
    loading: false,
  }),

  getters: {
    descriptorByFactoryPid: (state): Record<string, ComponentDescriptor> =>
      Object.fromEntries(
        state.descriptors.map((descriptor) => [descriptor.factoryPid, descriptor]),
      ),
  },

  actions: {
    async load(): Promise<void> {
      if (this.loading) {
        return
      }
      this.loading = true
      try {
        const metadata: WiresMetadata = await getMetadata()
        this.descriptors = (metadata.wireComponentDefinitions ?? []).map(toComponentDescriptor)
        this.loaded = true
      } finally {
        this.loading = false
      }
    },

    /** 组件标签：优先 descriptor.name，否则取 factoryPid 末段（对照上游 getComponentLabel） */
    labelOf(descriptor: ComponentDescriptor | null): string {
      if (!descriptor) {
        return ''
      }
      if (descriptor.name) {
        return descriptor.name
      }
      const segments = descriptor.factoryPid.split('.')
      return segments[segments.length - 1] ?? descriptor.factoryPid
    },

    /** 新建组件时的默认属性：取各字段的 metatype 默认值 */
    defaultProperties(descriptor: ComponentDescriptor | null): Record<string, unknown> {
      const result: Record<string, unknown> = {}
      for (const param of descriptor?.properties ?? ([] as ConfigParameter[])) {
        if (param.defaultValue !== undefined && param.defaultValue !== null) {
          result[param.id] = param.defaultValue
        }
      }
      return result
    },
  },
})
