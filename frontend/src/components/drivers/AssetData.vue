<script setup lang="ts">
/**
 * 资产数据页签（对照上游 AssetDataUi）：
 * Read 读全部可读通道 → 表格显示 name/type/valueType/status/value；
 * 可写通道（WRITE/READ_WRITE）可直接改值，改过的行标 Modified；
 * Write 前二次确认，只写改过的行；每行失败可点开原因。
 *
 * 状态取值与上游一致：Unknown / Success / Failure / Modified / Disabled；
 * 取值不可用时显示 "Not available"。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDriversAssetsStore } from '@/stores/driversAssets'
import type { AssetDataRow } from '@/types/driversAssets'

const emit = defineEmits<{
  (e: 'error', error: unknown): void
}>()

const store = useDriversAssetsStore()
const { t } = useI18n()

const rows = computed(() => store.dataRows)
const canWrite = computed(() => store.hasModifiedValues && !store.saving)

const STATUS_LABEL: Record<AssetDataRow['status'], string> = {
  UNKNOWN: 'driversAssets.data.statusUnknown',
  SUCCESS: 'driversAssets.data.statusSuccess',
  FAILURE: 'driversAssets.data.statusFailure',
  MODIFIED: 'driversAssets.data.statusModified',
  DISABLED: 'driversAssets.data.statusDisabled',
}

const STATUS_CLASS: Record<AssetDataRow['status'], string> = {
  UNKNOWN: 'is-unknown',
  SUCCESS: 'is-success',
  FAILURE: 'is-failure',
  MODIFIED: 'is-modified',
  DISABLED: 'is-disabled',
}

/** 该行是否可编辑取值（上游规则：只写通道不可编辑；读取失败的行也不可编辑） */
function isEditable(row: AssetDataRow): boolean {
  return row.type !== 'READ' && row.status !== 'DISABLED' && row.status !== 'FAILURE'
}

/** 展示值：成功直出，其余 "Not available" */
function displayValue(row: AssetDataRow): string {
  if (row.status === 'MODIFIED') {
    return row.edited
  }
  if (row.status !== 'SUCCESS') {
    return t('driversAssets.data.notAvailable')
  }
  return row.edited
}

function onEdit(index: number, value: string): void {
  store.setDataValue(index, value)
}

function read(): void {
  void store.readAssetData()
}

function write(): void {
  if (!canWrite.value) {
    return
  }
  ElMessageBox.confirm(
    t('driversAssets.data.writeConfirm', { pid: store.selectedAssetPid }),
    t('driversAssets.dialog.confirmTitle'),
    { type: 'warning', confirmButtonText: t('common.yes'), cancelButtonText: t('common.no') },
  )
    .then(() => store.writeAssetData())
    .then(() => {
      if (store.dataError) {
        ElMessage.error(store.dataError)
        return
      }
      ElMessage.success(t('driversAssets.message.written'))
    })
    .catch((error: unknown) => {
      if (error !== 'cancel') {
        emit('error', error)
      }
    })
}

/** 失败行点开原因（上游「Channel failure details」） */
function showFailure(row: AssetDataRow): void {
  if (row.status !== 'FAILURE') {
    return
  }
  void ElMessageBox.alert(
    `${t('driversAssets.data.reason')}${row.exceptionMessage || t('common.unknown')}`,
    t('driversAssets.data.failureTitle'),
  )
}
</script>

<template>
  <div class="asset-data">
    <div class="asset-data__header">
      <el-button type="primary" size="small" plain :loading="store.saving" @click="read">
        {{ $t('driversAssets.data.read') }}
      </el-button>
      <el-button type="primary" size="small" :disabled="!canWrite" @click="write">
        {{ $t('driversAssets.data.write') }}
      </el-button>
      <span class="asset-data__spacer" />
      <span v-if="store.dataError" class="asset-data__error">{{ store.dataError }}</span>
    </div>

    <p v-if="rows.length === 0" class="asset-data__empty">
      {{ $t('driversAssets.data.empty') }}
    </p>
    <el-table v-else :data="rows" size="small" border class="asset-data__table">
      <el-table-column :label="$t('driversAssets.data.columnName')" prop="name" min-width="120" />
      <el-table-column :label="$t('driversAssets.data.columnType')" prop="type" min-width="90" />
      <el-table-column
        :label="$t('driversAssets.data.columnValueType')"
        prop="valueType"
        min-width="90"
      />
      <el-table-column :label="$t('driversAssets.data.columnStatus')" min-width="140">
        <template #default="{ row }">
          <span
            class="asset-data__status"
            :class="STATUS_CLASS[row.status as AssetDataRow['status']]"
            :title="row.exceptionMessage ?? ''"
            @click="showFailure(row)"
          >
            {{ $t(STATUS_LABEL[row.status as AssetDataRow['status']]) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('driversAssets.data.columnValue')" min-width="160">
        <template #default="{ row, $index }">
          <el-input
            v-if="isEditable(row)"
            :model-value="displayValue(row)"
            size="small"
            @update:model-value="onEdit($index, $event)"
          />
          <span v-else class="asset-data__value">{{ displayValue(row) }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.asset-data__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.asset-data__spacer {
  flex: 1;
}

.asset-data__error {
  font-size: 12px;
  color: #f56c6c;
}

.asset-data__empty {
  font-size: 12px;
  color: #909399;
}

.asset-data__table {
  width: 100%;
}

.asset-data__status {
  font-size: 12px;
}

.asset-data__status.is-unknown,
.asset-data__status.is-failure {
  color: #f56c6c;
  cursor: pointer;
}

.asset-data__status.is-success {
  color: #67c23a;
}

.asset-data__status.is-modified {
  color: #e6a23c;
}

.asset-data__status.is-disabled {
  color: #909399;
}

.asset-data__value {
  font-size: 12px;
  color: #606266;
}
</style>
