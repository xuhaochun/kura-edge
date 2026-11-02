<script setup lang="ts">
/**
 * 资产配置页签（对照上游 AssetConfigurationUi）：
 * 通道表（列 = 基座参数 + 驱动参数，顺序由后端描述符决定）+ 新建/删除通道 + Apply/Reset。
 *
 * 上游的单元格编辑器规则：带 options 的参数走下拉、`+name` 只读、BOOLEAN 走 true/false 下拉、
 * 其余走文本输入。Apply 走后端「删掉再建」语义（重建实例），Reset 只回填不发请求。
 *
 * 本轮不做：CSV 下载/上传、列显隐弹窗（RFC 002 §6 明确推迟）。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import NewChannelDialog from './NewChannelDialog.vue'
import { useDriversAssetsStore } from '@/stores/driversAssets'
import type { ChannelColumn, ChannelRow } from '@/types/driversAssets'

const emit = defineEmits<{
  (e: 'error', error: unknown): void
}>()

const store = useDriversAssetsStore()
const { t } = useI18n()

const showNewChannel = ref(false)
const selectedIndex = ref(-1)

const columns = computed(() => store.assetColumns)
const rows = computed(() => store.channelRows)
const canApply = computed(() => store.canApply())
/** 通道表标题里的驱动标识（上游是 "Channels (org.eclipse.kura.driver.modbus)"） */
const driverLabel = computed(() => {
  const asset = store.assets.find((candidate) => candidate.pid === store.selectedAssetPid)
  const driver = store.drivers.find((candidate) => candidate.pid === asset?.driverPid)
  return driver?.factoryPid ?? ''
})

/** 参数可选值（BOOLEAN 也走这个通道） */
function optionsOf(column: ChannelColumn): Record<string, string> | null {
  if (column.type === 'BOOLEAN' && !column.options) {
    return { true: 'true', false: 'false' }
  }
  return column.options ?? null
}

/** 单元格是否只读（`+name` 由通道名派生，与上游一致） */
function isReadOnly(column: ChannelColumn): boolean {
  return column.id === '+name'
}

function cellValue(index: number, column: ChannelColumn): string {
  return rows.value[index]?.params[column.id] ?? ''
}

function setCell(index: number, column: ChannelColumn, value: string): void {
  store.setCell(index, column.id, value)
}

function onRowClick(row: ChannelRow): void {
  selectedIndex.value = rows.value.indexOf(row)
}

/**
 * 弹窗确认后把行名改成用户输入的名字：`addChannel` 先按默认 `Channel-N` 建行，
 * 名字不同则回写 `+name`（store 同时把行名改成新名字，属性键随之改变）。
 */
function onCreateChannel(name: string): void {
  const suggested = store.addChannel()
  if (suggested !== '' && suggested !== name) {
    store.setCell(store.channelRows.length - 1, '+name', name)
  }
  showNewChannel.value = false
}

function onDeleteChannel(): void {
  if (selectedIndex.value < 0) {
    return
  }
  store.removeChannel(selectedIndex.value)
  selectedIndex.value = -1
}

function apply(): void {
  if (!canApply.value) {
    ElMessage.warning(t('driversAssets.message.formInvalid'))
    return
  }
  store
    .applyAssetConfiguration()
    .then(() => ElMessage.success(t('driversAssets.message.applied')))
    .catch(emit.bind(null, 'error'))
}

function reset(): void {
  store.reloadChannelRows()
}
</script>

<template>
  <div class="asset-config">
    <div class="asset-config__header">
      <span class="asset-config__title">
        {{ $t('driversAssets.channels.title', { count: rows.length, driver: driverLabel }) }}
      </span>
      <span class="asset-config__spacer" />
      <el-button type="primary" size="small" plain @click="showNewChannel = true">
        {{ $t('driversAssets.channels.new') }}
      </el-button>
      <el-button size="small" plain :disabled="selectedIndex < 0" @click="onDeleteChannel">
        {{ $t('driversAssets.channels.delete') }}
      </el-button>
    </div>

    <p v-if="rows.length === 0" class="asset-config__empty">
      {{ $t('driversAssets.channels.empty') }}
    </p>
    <el-table
      v-else
      :data="rows"
      size="small"
      border
      highlight-current-row
      class="asset-config__table"
      @row-click="onRowClick"
    >
      <el-table-column
        v-for="column in columns"
        :key="column.id"
        :min-width="column.base ? 110 : 130"
      >
        <template #header>
          <span :title="column.description ?? column.name">{{ column.name }}</span>
        </template>
        <template #default="{ row, $index }">
          <span v-if="isReadOnly(column)" class="asset-config__readonly">{{ row.name }}</span>
          <el-select
            v-else-if="optionsOf(column)"
            :model-value="cellValue($index, column)"
            size="small"
            @update:model-value="setCell($index, column, $event)"
          >
            <el-option
              v-for="(label, value) in optionsOf(column)"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-input
            v-else
            :model-value="cellValue($index, column)"
            size="small"
            @update:model-value="setCell($index, column, $event)"
          />
        </template>
      </el-table-column>
    </el-table>

    <div class="asset-config__actions">
      <el-button
        type="primary"
        size="small"
        :disabled="!store.dirty"
        :loading="store.saving"
        @click="apply"
      >
        {{ $t('driversAssets.actions.apply') }}
      </el-button>
      <el-button size="small" :disabled="!store.dirty" @click="reset">
        {{ $t('driversAssets.actions.reset') }}
      </el-button>
      <span v-if="store.dirty" class="asset-config__dirty">
        {{ $t('driversAssets.channels.dirtyHint') }}
      </span>
    </div>

    <NewChannelDialog v-model="showNewChannel" @submit="onCreateChannel" />
  </div>
</template>

<style scoped>
.asset-config__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.asset-config__title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.asset-config__spacer {
  flex: 1;
}

.asset-config__empty {
  font-size: 12px;
  color: #909399;
}

.asset-config__table {
  width: 100%;
}

.asset-config__readonly {
  font-size: 12px;
  color: #606266;
}

.asset-config__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.asset-config__dirty {
  font-size: 12px;
  color: #e6a23c;
}
</style>
