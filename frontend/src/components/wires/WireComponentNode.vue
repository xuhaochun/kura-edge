<script setup lang="ts">
/**
 * 画布节点：组件名 + 多端口锚点（左侧输入 / 右侧输出，经 PortHandle 渲染）+ 删除按钮。
 * 图状态只读写 Pinia wiresGraph store（rules/05），不私存。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PortHandle from './PortHandle.vue'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import { useMetadataStore } from '@/stores/metadata'
import { useMetadataText } from '@/composables/useMetadataText'

export interface WireComponentNodeData {
  pid: string
  factoryPid: string
  inputPortCount: number
  outputPortCount: number
}

const props = defineProps<{
  id: string
  data: WireComponentNodeData
  selected?: boolean
}>()

const store = useWiresGraphStore()
const metadata = useMetadataStore()
const { t } = useI18n()
const { componentLabel } = useMetadataText()

/** 节点标题：i18n 中文组件名，未登记时回落后端名称，再退化为组件 pid */
const label = computed(() => {
  const descriptor = metadata.descriptorByFactoryPid[props.data.factoryPid] ?? null
  return componentLabel(props.data.factoryPid, metadata.labelOf(descriptor) || props.data.pid)
})

function onSelect(): void {
  store.select(props.id)
}

function onDelete(): void {
  store.removeComponent(props.id)
}
</script>

<template>
  <div class="wire-node" :class="{ 'wire-node--selected': props.selected }" @click.stop="onSelect">
    <button
      class="wire-node__delete"
      type="button"
      :title="t('wires.toolbar.deleteComponent')"
      @click.stop="onDelete"
    >
      ×
    </button>
    <div class="wire-node__label" :title="props.data.pid">{{ label }}</div>
    <PortHandle direction="in" :port-count="props.data.inputPortCount" />
    <PortHandle direction="out" :port-count="props.data.outputPortCount" />
  </div>
</template>

<style scoped>
.wire-node {
  position: relative;
  min-width: 120px;
  min-height: 56px;
  padding: 10px 16px;
  border: 1px solid #b7c0cd;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 1px 4px rgb(0 0 0 / 15%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.wire-node--selected {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgb(64 158 255 / 35%);
}

.wire-node__label {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wire-node__delete {
  position: absolute;
  top: -9px;
  right: -9px;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  background: #f56c6c;
  color: #fff;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  display: none;
}

.wire-node:hover .wire-node__delete,
.wire-node--selected .wire-node__delete {
  display: block;
}
</style>
