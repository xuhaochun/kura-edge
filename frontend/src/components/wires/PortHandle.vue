<script setup lang="ts">
/**
 * 多端口锚点（对照上游 wires_composer.js 的端口锚点布局 + WireComponentRenderingProperties）。
 *
 * 上游 Wires 的连线是 `(emitterPid, emitterPort) → (receiverPid, receiverPort)`，
 * 一个节点的同一侧有 N 个锚点（rules/08 §1 禁止简化为单端口）；本组件按 portCount
 * 渲染一侧的全部 Handle，handle id 由 portHandleId() 统一生成（in0/in1…、out0/out1…），
 * 与后端快照的 emitterPort / receiverPort（0 基）严格对应。
 *
 * 以 Fragment 渲染（无包装元素），保持 Handle 与节点元素（.wire-node）的父子关系，
 * 避免破坏 Vue Flow 对 handle 的定位与测量。
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { portHandleId } from '@/composables/canvas'

const props = defineProps<{
  /** 端口方向：in = 接收端（左侧 target），out = 发射端（右侧 source） */
  direction: 'in' | 'out'
  /** 该侧端口数量，来自组件配置的 inputPortCount / outputPortCount */
  portCount: number
}>()

const ports = computed(() => Array.from({ length: Math.max(0, props.portCount) }, (_, i) => i))

/**
 * 锚点沿节点边均匀分布：第 port 个锚点在 (port+1)/(count+1) 处，
 * 与上游按端口号排布锚点的效果一致。
 */
function anchorTop(port: number): string {
  return `${((port + 1) / (props.portCount + 1)) * 100}%`
}
</script>

<template>
  <Handle
    v-for="port in ports"
    :id="portHandleId(direction, port)"
    :key="`${direction}-${port}`"
    :type="direction === 'in' ? 'target' : 'source'"
    :position="direction === 'in' ? Position.Left : Position.Right"
    :style="{ top: anchorTop(port) }"
  />
</template>
