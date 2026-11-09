<script setup lang="ts">
/**
 * Drivers and Assets 页面（对照上游 DriversAndAssetsUi）：
 * 工具栏（New Driver / New Asset / Delete）+ 左侧实例列表 + 右侧选中项详情。
 *
 * 详情分两种：
 * - 驱动：配置表单（OCD 驱动）+ Apply/Reset；
 * - 资产：Configuration / Data 两个页签；配置有未保存改动时禁止切到 Data（上游同款规则）。
 *
 * 上游的具体字段与按钮启用规则在子组件里，页面只编排对话框、确认与全局提示。
 */
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import DriversAssetsList from '@/components/drivers/DriversAssetsList.vue'
import DriverConfiguration from '@/components/drivers/DriverConfiguration.vue'
import AssetConfiguration from '@/components/drivers/AssetConfiguration.vue'
import AssetData from '@/components/drivers/AssetData.vue'
import NewDriverDialog from '@/components/drivers/NewDriverDialog.vue'
import NewAssetDialog from '@/components/drivers/NewAssetDialog.vue'
import { useDriversAssetsStore } from '@/stores/driversAssets'
import { ApiError } from '@/api/http'

const store = useDriversAssetsStore()
const { t } = useI18n()

const showNewDriver = ref(false)
const showNewAsset = ref(false)
const activeTab = ref<'configuration' | 'data'>('configuration')

const selectedKind = computed(() => store.selected?.kind ?? null)
const selectedPid = computed(() => store.selected?.pid ?? '')
const selectedAsset = computed(() =>
  store.assets.find((asset) => asset.pid === store.selectedAssetPid),
)
/** 至少要有一个驱动实例才能建资产（资产必须挂在驱动上） */
const canCreateAsset = computed(() => store.drivers.length > 0)
/**
 * 新建资产弹窗里驱动下拉的默认值：
 * 选中资产 → 该资产所属驱动；选中驱动 → 该驱动；都没有 → 留空由用户在弹窗里选。
 */
const defaultAssetDriverPid = computed(() => {
  if (selectedAsset.value) {
    return selectedAsset.value.driverPid
  }
  return store.selected?.kind === 'driver' ? selectedPid.value : ''
})

onMounted(() => {
  void reload()
})

/** 首次加载列表；选中项变化时由列表组件触发配置加载 */
async function reload(): Promise<void> {
  await store.load()
  const first = store.drivers[0] ?? store.assets[0]
  if (first) {
    const kind: 'driver' | 'asset' = store.drivers.some((d) => d.pid === first.pid)
      ? 'driver'
      : 'asset'
    await store.select(kind, first.pid)
    if (kind === 'asset') {
      store.reloadChannelRows()
    }
  }
}

function onSelect(kind: 'driver' | 'asset', pid: string): void {
  void store.select(kind, pid).then(() => {
    activeTab.value = 'configuration'
    if (kind === 'asset') {
      store.reloadChannelRows()
    }
  })
}

function onCreateDriver(payload: { factoryPid: string; pid: string }): void {
  void store
    .addDriver(payload.factoryPid, payload.pid)
    .then(() => {
      showNewDriver.value = false
      activeTab.value = 'configuration'
      ElMessage.success(t('driversAssets.message.created'))
    })
    .catch(showError)
}

function onCreateAsset(payload: { pid: string; driverPid: string; description: string }): void {
  if (!payload.driverPid) {
    ElMessage.warning(t('driversAssets.validation.driverRequired'))
    return
  }
  void store
    .addAsset(payload.pid, payload.driverPid, payload.description)
    .then(() => {
      showNewAsset.value = false
      store.reloadChannelRows()
      activeTab.value = 'configuration'
      ElMessage.success(t('driversAssets.message.created'))
    })
    .catch(showError)
}

function onDelete(): void {
  if (!store.selected) {
    return
  }
  ElMessageBox.confirm(t('driversAssets.dialog.confirmDelete', { pid: selectedPid.value }), {
    title: t('driversAssets.dialog.confirmTitle'),
    type: 'warning',
    confirmButtonText: t('common.yes'),
    cancelButtonText: t('common.no'),
  })
    .then(() => store.removeSelected())
    .then(() => ElMessage.success(t('driversAssets.message.deleted')))
    .catch((error: unknown) => {
      if (error !== 'cancel') {
        showError(error)
      }
    })
}

/** 切到 Data 页签前拦截「配置有未保存改动」（上游 driversAssetsAssetConfigDirty） */
function switchTab(name: string | number): void {
  if (name === 'data' && store.dirty) {
    ElMessage.warning(t('driversAssets.message.configDirty'))
    return
  }
  activeTab.value = name === 'data' ? 'data' : 'configuration'
  if (name === 'data') {
    void store.readAssetData()
  }
}

function showError(error: unknown): void {
  if (error instanceof ApiError) {
    ElMessage.error(error.message)
    return
  }
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

/** 离开页面时提示未保存改动（对照上游 confirmIfUiDirty） */
onBeforeRouteLeave(() => {
  if (!store.hasUnsavedChanges) {
    return true
  }
  return ElMessageBox.confirm(t('driversAssets.message.discardChanges'), {
    title: t('driversAssets.dialog.confirmTitle'),
    type: 'warning',
    confirmButtonText: t('common.yes'),
    cancelButtonText: t('common.no'),
  })
    .then(() => true)
    .catch(() => false)
})
</script>

<template>
  <div class="drivers-assets">
    <header class="drivers-assets__toolbar">
      <el-button type="primary" size="small" @click="showNewDriver = true">
        {{ $t('driversAssets.toolbar.newDriver') }}
      </el-button>
      <el-button
        type="primary"
        size="small"
        plain
        :disabled="!canCreateAsset"
        @click="showNewAsset = true"
      >
        {{ $t('driversAssets.toolbar.newAsset') }}
      </el-button>
      <el-button type="danger" size="small" plain :disabled="!store.selected" @click="onDelete">
        {{ $t('driversAssets.toolbar.delete') }}
      </el-button>
      <span class="drivers-assets__intro">{{ $t('driversAssets.intro') }}</span>
    </header>

    <div class="drivers-assets__body">
      <aside class="drivers-assets__list">
        <DriversAssetsList @select="onSelect" />
      </aside>

      <main class="drivers-assets__detail">
        <el-empty
          v-if="!store.selected"
          :description="$t('driversAssets.detail.none')"
          :image-size="60"
        />
        <DriverConfiguration
          v-else-if="selectedKind === 'driver'"
          :key="`driver:${selectedPid}`"
          @error="showError"
        />
        <el-tabs
          v-else
          :model-value="activeTab"
          class="drivers-assets__tabs"
          @tab-change="switchTab"
        >
          <el-tab-pane :label="$t('driversAssets.tabs.configuration')" name="configuration">
            <AssetConfiguration :key="`asset:${selectedPid}`" @error="showError" />
          </el-tab-pane>
          <el-tab-pane :label="$t('driversAssets.tabs.data')" name="data">
            <AssetData :key="`data:${selectedPid}`" @error="showError" />
          </el-tab-pane>
        </el-tabs>
      </main>
    </div>

    <NewDriverDialog v-model="showNewDriver" @submit="onCreateDriver" />
    <NewAssetDialog
      v-model="showNewAsset"
      :drivers="store.drivers"
      :default-driver-pid="defaultAssetDriverPid"
      @submit="onCreateAsset"
    />
  </div>
</template>

<style scoped>
.drivers-assets {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.drivers-assets__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #dcdfe6;
}

.drivers-assets__intro {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.drivers-assets__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.drivers-assets__list {
  width: 340px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid #dcdfe6;
  overflow: auto;
}

.drivers-assets__detail {
  flex: 1;
  min-width: 0;
  padding: 12px 16px;
  overflow: auto;
  background: #fff;
  margin: 8px;
  border: 1px solid #ebeef5;
}

.drivers-assets__tabs {
  height: 100%;
}
</style>
