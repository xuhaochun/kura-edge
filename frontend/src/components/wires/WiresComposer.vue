<script setup lang="ts">
/**
 * 画布（对照上游 wires_composer.js）：
 * 节点/连线全部由 Pinia wiresGraph store 派生，事件回写 store；
 * 建线逐端口（handle id = 端口号），成环/重复/端口越界由 store 校验拒绝；
 * 网格吸附 snapToGrid（20px，同上游 clampToGrid）；缩放/平移/fitView。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type {
  Connection,
  Edge,
  EdgeMouseEvent,
  Node,
  NodeDragEvent,
  NodeMouseEvent,
} from '@vue-flow/core'
import WireComponentNode from './WireComponentNode.vue'
import {
  GRID_SIZE,
  parsePortHandle,
  parseWireEdgeId,
  portHandleId,
  wireEdgeId,
} from '@/composables/canvas'
import { useWiresGraphStore } from '@/stores/wiresGraph'

const emit = defineEmits<{
  (
    e: 'create',
    payload: { factoryPid: string; pid?: string; position: { x: number; y: number } },
  ): void
}>()

const store = useWiresGraphStore()
const { t } = useI18n()
const { zoomIn, zoomOut, fitView, screenToFlowCoordinate } = useVueFlow()

const flowNodes = computed<Node[]>(() =>
  store.components.map((component) => ({
    id: component.pid,
    type: 'wire-component',
    position: { ...component.position },
    selected: store.selectedPid === component.pid,
    data: {
      pid: component.pid,
      factoryPid: component.factoryPid,
      inputPortCount: component.inputPortCount,
      outputPortCount: component.outputPortCount,
    },
  })),
)

const flowEdges = computed<Edge[]>(() =>
  store.wires.map((wire) => ({
    id: wireEdgeId(wire),
    source: wire.emitterPid,
    target: wire.receiverPid,
    sourceHandle: portHandleId('out', wire.emitterPort),
    targetHandle: portHandleId('in', wire.receiverPort),
    animated: true,
  })),
)

function onConnect(connection: Connection): void {
  try {
    store.addWire({
      emitterPid: connection.source,
      receiverPid: connection.target,
      emitterPort: parsePortHandle(connection.sourceHandle, 'out'),
      receiverPort: parsePortHandle(connection.targetHandle, 'in'),
    })
  } catch (error) {
    ElMessage.error(t((error as Error).message))
  }
}

function onEdgeClick(event: EdgeMouseEvent): void {
  const wire = parseWireEdgeId(event.edge.id)
  if (wire) {
    store.removeWire(wire)
  }
}

function onNodeClick(event: NodeMouseEvent): void {
  store.select(event.node.id)
}

function onDragStop(event: NodeDragEvent): void {
  store.moveComponent(event.node.id, event.node.position)
}

/** 面板拖拽落点：屏幕坐标 → 流程图坐标（对照上游 onDrop） */
function onDrop(event: DragEvent): void {
  const payload = event.dataTransfer?.getData('application/x-wires-node')
  if (!payload) {
    return
  }
  let parsed: { factoryPid?: string; pid?: string }
  try {
    parsed = JSON.parse(payload) as { factoryPid?: string; pid?: string }
  } catch {
    return
  }
  if (!parsed.factoryPid) {
    return
  }
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  emit('create', { factoryPid: parsed.factoryPid, pid: parsed.pid, position })
}

defineExpose({ zoomIn, zoomOut, fitView })
</script>

<template>
  <div class="wires-composer">
    <VueFlow
      class="wires-composer__flow"
      :nodes="flowNodes"
      :edges="flowEdges"
      :snap-to-grid="true"
      :snap-grid="[GRID_SIZE, GRID_SIZE]"
      :default-viewport="{ zoom: 1 }"
      fit-view-on-init
      @connect="onConnect"
      @edge-click="onEdgeClick"
      @node-click="onNodeClick"
      @pane-click="store.deselect()"
      @node-drag-stop="onDragStop"
      @dragover.prevent
      @drop="onDrop"
    >
      <template #node-wire-component="nodeProps">
        <WireComponentNode v-bind="nodeProps" />
      </template>
    </VueFlow>
  </div>
</template>

<style scoped>
.wires-composer,
.wires-composer__flow {
  width: 100%;
  height: 100%;
}

.wires-composer__flow {
  background-image:
    linear-gradient(to right, rgb(0 0 0 / 6%) 1px, transparent 1px),
    linear-gradient(to bottom, rgb(0 0 0 / 6%) 1px, transparent 1px);
  background-size: 20px 20px;
}
</style>
