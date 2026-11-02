<script setup lang="ts">
/**
 * Wires 图编辑器页（对照上游 WiresPanelUi 布局）：
 * 工具栏（保存/重新加载/删除整图/导出/导入/缩放/删除组件）+ 组件面板 + 画布 + 配置区；
 * 未保存变更离开提示（onBeforeRouteLeave + beforeunload）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import WiresPalette from '@/components/wires/WiresPalette.vue'
import WiresComposer from '@/components/wires/WiresComposer.vue'
import ConfigurationArea from '@/components/wires/ConfigurationArea.vue'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import { useMetadataStore } from '@/stores/metadata'
import { useSnapshot } from '@/composables/useSnapshot'
import type { ComponentDescriptor } from '@/types/wires'

/** 画布坐标 */
interface Position {
  x: number
  y: number
}

const store = useWiresGraphStore()
const metadata = useMetadataStore()
const { t } = useI18n()
const { loading, saving, load, save, deleteAll, exportJson, importJson, createComponent } =
  useSnapshot()

const composerRef = ref<InstanceType<typeof WiresComposer> | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

function confirmThen(messageKey: string, action: () => void): void {
  ElMessageBox.confirm(t(messageKey), t('app.title'), { type: 'warning' })
    .then(() => action())
    .catch(() => undefined)
}

/** 面板点击新建：位置按现有组件数错开（store 内部再做网格吸附） */
function onPaletteCreate(payload: { descriptor: ComponentDescriptor; pid?: string }): void {
  const offset = store.components.length
  void addNode(payload.descriptor, payload.pid, {
    x: 80 + (offset % 5) * 160,
    y: 80 + Math.floor(offset / 5) * 120,
  })
}

function onDropCreate(payload: {
  factoryPid: string
  pid?: string
  position: { x: number; y: number }
}): void {
  const descriptor = metadata.descriptors.find(
    (candidate) => candidate.factoryPid === payload.factoryPid,
  )
  if (descriptor) {
    void addNode(descriptor, payload.pid, payload.position)
  }
}

/**
 * 加节点：带 pid 的（面板块里的已有资产）复用该 pid —— 同一资产不能重复入图，
 * 重复时给提示而不是悄悄加第二个节点（保存时后端会因重复 pid 报错）。
 */
async function addNode(
  descriptor: ComponentDescriptor,
  pid: string | undefined,
  position: Position,
): Promise<void> {
  if (pid && store.components.some((component) => component.pid === pid)) {
    ElMessage.warning(t('wires.messages.nodeAlreadyInGraph', { pid }))
    return
  }
  await createComponent(descriptor, position, pid)
}

function onDeleteComponent(): void {
  const pid = store.selectedPid
  if (!pid) {
    return
  }
  confirmThen('wires.dialog.confirmDeleteComponent', () => store.removeComponent(pid))
}

function onImportFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    void importJson(file)
  }
}

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!store.isDirty) {
    return
  }
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  void metadata.load().then(() => load())
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})

onBeforeRouteLeave(async () => {
  if (!store.isDirty) {
    return true
  }
  try {
    await ElMessageBox.confirm(t('wires.dialog.confirmLeave'), t('app.title'), { type: 'warning' })
    return true
  } catch {
    return false
  }
})
</script>

<template>
  <div class="wires-page">
    <header class="wires-page__toolbar">
      <el-button
        type="primary"
        size="small"
        :loading="saving"
        :disabled="!store.hasComponents"
        @click="save()"
      >
        {{ $t('wires.toolbar.save') }}
      </el-button>
      <el-button
        size="small"
        :loading="loading"
        @click="confirmThen('wires.dialog.confirmReset', () => load())"
      >
        {{ $t('wires.toolbar.reload') }}
      </el-button>
      <el-button size="small" @click="exportJson()">{{ $t('wires.toolbar.download') }}</el-button>
      <el-button size="small" @click="fileInputRef?.click()">
        {{ $t('wires.toolbar.importJson') }}
      </el-button>
      <el-button
        type="danger"
        size="small"
        plain
        @click="confirmThen('wires.dialog.confirmDeleteGraph', () => deleteAll())"
      >
        {{ $t('wires.toolbar.deleteGraph') }}
      </el-button>
      <el-button
        type="danger"
        size="small"
        plain
        :disabled="!store.selectedPid"
        @click="onDeleteComponent"
      >
        {{ $t('wires.toolbar.deleteComponent') }}
      </el-button>
      <span class="wires-page__spacer" />
      <el-button size="small" @click="composerRef?.zoomOut()">－</el-button>
      <el-button size="small" @click="composerRef?.zoomIn()">＋</el-button>
      <el-button size="small" @click="composerRef?.fitView()">
        {{ $t('wires.toolbar.zoomFit') }}
      </el-button>
      <input
        ref="fileInputRef"
        class="wires-page__file-input"
        type="file"
        accept="application/json,.json"
        @change="onImportFile"
      />
    </header>
    <div class="wires-page__body">
      <aside class="wires-page__left">
        <WiresPalette @create="onPaletteCreate" />
      </aside>
      <main class="wires-page__canvas">
        <WiresComposer ref="composerRef" @create="onDropCreate" />
      </main>
      <aside class="wires-page__right">
        <ConfigurationArea />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.wires-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.wires-page__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-bottom: 1px solid #dcdfe6;
  background: #fff;
}

.wires-page__spacer {
  flex: 1;
}

.wires-page__file-input {
  display: none;
}

.wires-page__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.wires-page__left {
  width: 240px;
  flex-shrink: 0;
}

.wires-page__canvas {
  flex: 1;
  min-width: 0;
}

.wires-page__right {
  width: 460px;
  flex-shrink: 0;
}
</style>
