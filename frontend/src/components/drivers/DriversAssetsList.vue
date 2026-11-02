<script setup lang="ts">
/**
 * 实例列表（对照上游 DriversAndAssetsListUi）：
 * 三列 Service PID / Type / Factory PID；资产以「 -> pid」缩进挂在所属驱动下面。
 * 单选、点击切换；没有驱动时显示 "No Drivers available!"（上游同款文案）。
 */
import { computed } from 'vue'
import { useDriversAssetsStore } from '@/stores/driversAssets'

const emit = defineEmits<{
  (e: 'select', kind: 'driver' | 'asset', pid: string): void
}>()

const store = useDriversAssetsStore()

interface Row {
  kind: 'driver' | 'asset'
  pid: string
  factoryPid: string
  typeLabel: string
  nested: boolean
}

const rows = computed<Row[]>(() => {
  const grouped = store.assetsByDriver
  const result: Row[] = []
  for (const driver of store.drivers) {
    result.push({
      kind: 'driver',
      pid: driver.pid,
      factoryPid: driver.factoryPid,
      typeLabel: 'Driver',
      nested: false,
    })
    for (const asset of grouped[driver.pid] ?? []) {
      result.push({
        kind: 'asset',
        pid: asset.pid,
        factoryPid: asset.factoryPid,
        typeLabel: 'Asset',
        nested: true,
      })
    }
  }
  // 绑定到不存在的驱动的资产也要能显示（避免"配置存在但界面看不到"）
  for (const asset of store.assets) {
    if (!store.drivers.some((driver) => driver.pid === asset.driverPid)) {
      result.push({
        kind: 'asset',
        pid: asset.pid,
        factoryPid: asset.factoryPid,
        typeLabel: 'Asset',
        nested: false,
      })
    }
  }
  return result
})

const currentKey = computed(() =>
  store.selected ? `${store.selected.kind}:${store.selected.pid}` : '',
)

function keyOf(row: Row): string {
  return `${row.kind}:${row.pid}`
}

function onSelect(row: Row): void {
  emit('select', row.kind, row.pid)
}
</script>

<template>
  <div class="da-list">
    <div class="da-list__head">
      <span class="da-list__title">{{ $t('driversAssets.list.title') }}</span>
      <span class="da-list__count">{{ rows.length }}</span>
    </div>
    <el-empty
      v-if="rows.length === 0"
      :description="$t('driversAssets.list.noDrivers')"
      :image-size="50"
    />
    <ul v-else class="da-list__rows">
      <li
        v-for="row in rows"
        :key="keyOf(row)"
        class="da-list__row"
        :class="{
          'da-list__row--active': keyOf(row) === currentKey,
          'da-list__row--nested': row.nested,
          'da-list__row--invalid': !row.factoryPid,
        }"
        @click="onSelect(row)"
      >
        <span class="da-list__pid">
          <span v-if="row.nested" class="da-list__arrow">-&gt;</span>
          {{ row.pid }}
        </span>
        <span class="da-list__type">{{ row.typeLabel }}</span>
        <span class="da-list__factory">{{ row.factoryPid || $t('common.unknown') }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.da-list {
  padding: 8px 0;
}

.da-list__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 0 12px 6px;
  border-bottom: 1px solid #ebeef5;
}

.da-list__title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.da-list__count {
  font-size: 12px;
  color: #909399;
}

.da-list__rows {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.da-list__row {
  display: grid;
  grid-template-columns: 1.4fr 0.6fr 1fr;
  gap: 8px;
  padding: 6px 12px;
  font-size: 12px;
  color: #303133;
  cursor: pointer;
  border-left: 3px solid transparent;
}

.da-list__row:hover {
  background: #f5f7fa;
}

.da-list__row--active {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.da-list__row--nested {
  padding-left: 24px;
  color: #606266;
}

.da-list__row--invalid .da-list__factory {
  color: #f56c6c;
}

.da-list__pid {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.da-list__arrow {
  color: #909399;
  margin-right: 4px;
}

.da-list__type {
  color: #606266;
}

.da-list__factory {
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
