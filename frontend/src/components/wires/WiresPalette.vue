<script setup lang="ts">
/**
 * 组件面板：按 metadata 的 factoryPid 分组展示组件定义；点击 → emit create，拖拽 → dataTransfer 携带节点信息。
 *
 * 资产在面板里**单独一组**（对照上游 wires composer 的 Assets 区）：列出已经存在的资产实例，
 * 拖/点即把该实例加入线图（复用它的 PID）；通用组件组里不再出现 WireAsset 工厂，
 * 避免"从面板新建一个没有 driver.pid 的空资产"。
 */
import { computed, onMounted, ref } from 'vue'
import { useMetadataStore } from '@/stores/metadata'
import { useMetadataText } from '@/composables/useMetadataText'
import { getAssetInfos } from '@/api/driversAssets'
import { PALETTE_MIME, WIRE_ASSET_FACTORY_PID } from '@/types/driversAssets'
import type { PidAndFactoryPidDto } from '@/types/driversAssets'
import type { ComponentDescriptor } from '@/types/wires'

const emit = defineEmits<{
  (e: 'create', payload: { descriptor: ComponentDescriptor; pid?: string }): void
}>()

const metadata = useMetadataStore()
const { componentLabel } = useMetadataText()
const assets = ref<PidAndFactoryPidDto[]>([])

onMounted(() => {
  void getAssetInfos()
    .then((result) => {
      assets.value = result
    })
    .catch(() => {
      assets.value = []
    })
})

/** 资产组用 WireAsset 的定义（配置表单要它的 OCD），但不出现在通用组件列表里 */
const assetDescriptor = computed(
  () =>
    metadata.descriptors.find((descriptor) => descriptor.factoryPid === WIRE_ASSET_FACTORY_PID) ??
    null,
)

/** 通用组件组：排除资产工厂 */
const groups = computed(() =>
  metadata.descriptors
    .filter((descriptor) => descriptor.factoryPid !== WIRE_ASSET_FACTORY_PID)
    .map((descriptor) => ({
      factoryPid: descriptor.factoryPid,
      label: componentLabel(descriptor.factoryPid, metadata.labelOf(descriptor)),
      descriptor,
    })),
)

function onCreate(descriptor: ComponentDescriptor, pid?: string): void {
  emit('create', { descriptor, pid })
}

function onDragStart(event: DragEvent, descriptor: ComponentDescriptor, pid?: string): void {
  event.dataTransfer?.setData(
    PALETTE_MIME,
    JSON.stringify({ factoryPid: descriptor.factoryPid, pid }),
  )
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copy'
  }
}
</script>

<template>
  <div class="wires-palette">
    <div class="wires-palette__title">{{ $t('wires.palette.title') }}</div>
    <div class="wires-palette__hint">{{ $t('wires.palette.hint') }}</div>
    <p v-if="groups.length === 0" class="wires-palette__empty">{{ $t('wires.palette.empty') }}</p>
    <section v-for="group in groups" :key="group.factoryPid" class="wires-palette__group">
      <div class="wires-palette__group-name">{{ group.label }}</div>
      <button
        class="wires-palette__item"
        type="button"
        draggable="true"
        @click="onCreate(group.descriptor)"
        @dragstart="onDragStart($event, group.descriptor)"
      >
        <span class="wires-palette__item-label">{{ group.label }}</span>
        <span class="wires-palette__item-pid">{{ group.factoryPid }}</span>
      </button>
    </section>

    <section
      v-if="assetDescriptor && assets.length > 0"
      class="wires-palette__group wires-palette__group--assets"
    >
      <div class="wires-palette__group-name">{{ $t('wires.palette.assets') }}</div>
      <button
        v-for="asset in assets"
        :key="asset.pid"
        class="wires-palette__item"
        type="button"
        draggable="true"
        @click="onCreate(assetDescriptor, asset.pid)"
        @dragstart="onDragStart($event, assetDescriptor, asset.pid)"
      >
        <span class="wires-palette__item-label">{{ asset.pid }}</span>
        <span class="wires-palette__item-pid">{{ asset.driverPid }}</span>
      </button>
    </section>
  </div>
</template>

<style scoped>
.wires-palette {
  padding: 8px 12px;
}

.wires-palette__title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.wires-palette__hint,
.wires-palette__empty {
  font-size: 12px;
  color: #909399;
  margin: 4px 0 8px;
}

.wires-palette__group {
  margin-bottom: 10px;
}

.wires-palette__group-name {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 4px;
}

.wires-palette__group--assets {
  border-top: 1px dashed #dcdfe6;
  padding-top: 8px;
}

.wires-palette__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  margin-bottom: 4px;
  padding: 4px 6px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  cursor: grab;
  text-align: left;
}

.wires-palette__item:hover {
  border-color: #409eff;
}

.wires-palette__item-label {
  font-size: 12px;
  color: #303133;
}

.wires-palette__item-pid {
  font-size: 11px;
  color: #909399;
  word-break: break-all;
}
</style>
