<script setup lang="ts">
/**
 * Wire Graph 选中资产时的配置区（对照上游 ConfigurationAreaUi 的两个页签）：
 * - 资产页签：资产级字段（asset.desc / emit.*）+ 驱动下拉 + 通道表；
 * - Driver 页签：选中驱动的 OCD 表单，Apply 走既有 PUT /wires/drivers/{pid}。
 *
 * 资产页签的改动只写回 wiresGraph store 并置脏，随整图保存落库（上游同样是组件配置随图保存）；
 * 驱动是独立实例，因此 Driver 页签 Apply 立即保存并重建驱动实例。
 * 通道表的纯逻辑复用 composables/useAssetChannels，与应用内其它资产表同源。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import ValidationInput from './ValidationInput.vue'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import { toConfigParameter } from '@/stores/metadata'
import { toFormValues, toPropertyMap } from '@/stores/driversAssets'
import { useMetadataText } from '@/composables/useMetadataText'
import {
  createChannelRow,
  isChannelTableValid,
  parseChannelRows,
  serializeChannelRows,
  suggestChannelName,
  toCellString,
  toChannelColumns,
  validateChannelName,
  type ChannelNameError,
} from '@/composables/useAssetChannels'
import { toPropertyScalar, validateConfiguration } from '@/composables/useGraphValidation'
import {
  getAssetConfiguration,
  getDriverConfiguration,
  getDriverInfos,
  putDriverConfiguration,
} from '@/api/driversAssets'
import { getMetadata } from '@/api/metadata'
import { WIRE_ASSET_FACTORY_PID } from '@/types/driversAssets'
import type { Ad, ChannelColumn, ChannelRow, PidAndFactoryPidDto } from '@/types/driversAssets'
import type { ConfigParameter, WiresMetadata } from '@/types/wires'

const props = defineProps<{
  pid: string
}>()

const graph = useWiresGraphStore()
const { t } = useI18n()
const { propertyLabel, propertyDescription } = useMetadataText()

const metadata = ref<WiresMetadata | null>(null)
const drivers = ref<PidAndFactoryPidDto[]>([])
const driverPid = ref('')
const assetValues = ref<Record<string, string>>({})
const rows = ref<ChannelRow[]>([])
const columns = ref<ChannelColumn[]>([])
const selectedRow = ref(-1)
const activeTab = ref<'asset' | 'driver'>('asset')
const loading = ref(false)

const driverRaw = ref<Record<string, unknown>>({})
const driverValues = ref<Record<string, string>>({})
const driverInitial = ref<Record<string, string>>({})

const showAddChannel = ref(false)
const newChannelName = ref('')

const component = computed(() => graph.componentByPid(props.pid))

const assetParameters = computed<ConfigParameter[]>(() => {
  const definition = metadata.value?.wireComponentDefinitions?.find(
    (candidate) => candidate.factoryPid === WIRE_ASSET_FACTORY_PID,
  )
  return (definition?.componentOcd ?? [])
    .map(toConfigParameter)
    .filter((parameter) => parameter.id !== 'driver.pid')
})

const driverParameters = computed<ConfigParameter[]>(() => {
  const driver = drivers.value.find((candidate) => candidate.pid === driverPid.value)
  if (!driver) {
    return []
  }
  const definition = metadata.value?.driverOCDs?.find((entry) => entry.pid === driver.factoryPid)
  return (definition?.definition?.ads ?? []).map(toConfigParameter)
})

const driverTabLabel = computed(() =>
  t('wires.config.driverTab', { pid: driverPid.value === '' ? '?' : driverPid.value }),
)

/** 通道表标题用驱动实例 PID（对照上游 channelTableTitle 取 driver.pid） */
const driverLabel = computed(() => driverPid.value)

const driverDirty = computed(
  () => JSON.stringify(driverValues.value) !== JSON.stringify(driverInitial.value),
)

function driverFieldError(parameter: ConfigParameter): string {
  if (parameter.required && (driverValues.value[parameter.id] ?? '').trim() === '') {
    return t('driversAssets.validation.required')
  }
  return ''
}

const driverValid = computed(() =>
  driverParameters.value.every((parameter) => driverFieldError(parameter) === ''),
)

const newChannelError = computed<string>(() => {
  const error: ChannelNameError = validateChannelName(newChannelName.value, rows.value)
  return error === '' ? '' : t(`driversAssets.validation.channelName.${error}`)
})

watch(
  () => props.pid,
  () => {
    activeTab.value = 'asset'
    void initialize()
  },
  { immediate: true },
)

async function initialize(): Promise<void> {
  if (!component.value) {
    return
  }
  loading.value = true
  try {
    if (!metadata.value) {
      metadata.value = await getMetadata()
    }
    drivers.value = await getDriverInfos()
    if (!component.value.properties['driver.pid']) {
      // 兼容旧快照/直接加入画布的资产：从配置服务补回真实属性，避免保存时冲掉已有通道
      try {
        graph.updateProperties(props.pid, await getAssetConfiguration(props.pid))
      } catch {
        // 资产配置不存在时保留画布上的默认属性，Save 时由后端报错
      }
    }
    resetAssetFromComponent()
    refreshColumns()
    await loadDriverConfiguration()
  } catch (error) {
    ElMessage.error(t('wires.config.assetLoadFailed', { msg: messageOf(error) }))
  } finally {
    loading.value = false
  }
}

function resetAssetFromComponent(): void {
  const current = component.value
  if (!current) {
    return
  }
  const values: Record<string, string> = {}
  for (const parameter of assetParameters.value) {
    values[parameter.id] = toCellString(current.properties[parameter.id] ?? parameter.defaultValue)
  }
  assetValues.value = values
  driverPid.value = toCellString(current.properties['driver.pid'])
  rows.value = parseChannelRows(current.properties)
  selectedRow.value = -1
}

/** 基座通道参数 + 当前驱动的通道参数（顺序与后端一致：基座在前） */
function refreshColumns(): void {
  const base: Ad[] = metadata.value?.assetChannelDescriptor ?? []
  const driverDescriptor = metadata.value?.driverChannelDescriptors?.find(
    (entry) => entry.pid === driverPid.value,
  )
  const merged = [...base, ...(driverDescriptor?.channelDescriptor ?? [])]
  columns.value = toChannelColumns(merged)
  rows.value = normalizeRows(rows.value, columns.value)
}

/** 保证每行都带齐当前列的默认值；切驱动时丢弃旧驱动的通道参数 */
function normalizeRows(existing: ChannelRow[], definitions: ChannelColumn[]): ChannelRow[] {
  return existing.map((row) => {
    const params: Record<string, string> = {}
    for (const column of definitions) {
      params[column.id] = row.params[column.id] ?? toCellString(column.defaultValue)
    }
    params['+name'] = row.name
    return { name: row.name, params }
  })
}

function onDriverChange(value: string): void {
  driverPid.value = value
  refreshColumns()
  void loadDriverConfiguration()
}

async function loadDriverConfiguration(): Promise<void> {
  driverRaw.value = {}
  driverValues.value = {}
  driverInitial.value = {}
  if (driverPid.value === '') {
    return
  }
  try {
    const properties = await getDriverConfiguration(driverPid.value)
    driverRaw.value = properties
    const form = toFormValues(properties, driverParameters.value)
    driverValues.value = { ...form }
    driverInitial.value = { ...form }
  } catch (error) {
    ElMessage.error(t('wires.config.driverLoadFailed', { msg: messageOf(error) }))
  }
}

function assetFieldLabel(parameter: ConfigParameter): string {
  return propertyLabel(parameter, WIRE_ASSET_FACTORY_PID)
}

function assetFieldDescription(parameter: ConfigParameter): string {
  return propertyDescription(parameter, WIRE_ASSET_FACTORY_PID)
}

function assetFieldError(parameter: ConfigParameter): string {
  const result = validateConfiguration([parameter], assetValues.value)[parameter.id]
  if (!result || result.valid) {
    return ''
  }
  return t(result.messageKey ?? '', result.params ?? {})
}

function optionsOf(column: ChannelColumn): Record<string, string> | null {
  if (column.type === 'BOOLEAN' && !column.options) {
    return { true: 'true', false: 'false' }
  }
  return column.options ?? null
}

function isReadOnly(column: ChannelColumn): boolean {
  return column.id === '+name'
}

function cellValue(index: number, column: ChannelColumn): string {
  return rows.value[index]?.params[column.id] ?? ''
}

function setCell(index: number, column: ChannelColumn, value: string): void {
  const row = rows.value[index]
  if (row) {
    row.params[column.id] = value
  }
}

function onRowClick(row: ChannelRow): void {
  selectedRow.value = rows.value.indexOf(row)
}

function openAddChannel(): void {
  newChannelName.value = suggestChannelName(rows.value)
  showAddChannel.value = true
}

function addChannel(): void {
  if (newChannelError.value !== '') {
    return
  }
  const row = createChannelRow(newChannelName.value, columns.value)
  rows.value.push(row)
  selectedRow.value = rows.value.length - 1
  showAddChannel.value = false
}

function removeChannel(): void {
  if (selectedRow.value < 0) {
    return
  }
  rows.value.splice(selectedRow.value, 1)
  selectedRow.value = -1
}

function applyAsset(): void {
  const current = component.value
  if (!current) {
    return
  }
  if (driverPid.value === '') {
    ElMessage.warning(t('wires.config.driverRequired'))
    return
  }
  if (assetParameters.value.some((parameter) => assetFieldError(parameter) !== '')) {
    ElMessage.warning(t('wires.config.invalid'))
    return
  }
  if (!isChannelTableValid(rows.value, columns.value)) {
    ElMessage.warning(t('wires.config.channelsInvalid'))
    return
  }
  const base: Record<string, unknown> = { ...current.properties }
  for (const parameter of assetParameters.value) {
    base[parameter.id] = toPropertyScalar(parameter, assetValues.value[parameter.id])
  }
  base['driver.pid'] = driverPid.value
  graph.updateProperties(current.pid, serializeChannelRows(base, rows.value, columns.value))
  ElMessage.success(t('wires.config.applySuccess'))
}

function resetAsset(): void {
  resetAssetFromComponent()
  refreshColumns()
}

function applyDriver(): void {
  if (driverPid.value === '') {
    return
  }
  const properties = toPropertyMap(driverRaw.value, driverParameters.value, driverValues.value)
  void putDriverConfiguration(driverPid.value, properties)
    .then(() => {
      driverRaw.value = properties
      driverInitial.value = { ...driverValues.value }
      ElMessage.success(t('wires.config.driverApplySuccess'))
      refreshColumns()
      return loadDriverConfiguration()
    })
    .catch((error: unknown) => {
      ElMessage.error(t('wires.config.driverLoadFailed', { msg: messageOf(error) }))
    })
}

function resetDriver(): void {
  driverValues.value = { ...driverInitial.value }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
</script>

<template>
  <div v-loading="loading" class="asset-config">
    <el-tabs v-model="activeTab" class="asset-config__tabs">
      <el-tab-pane :label="$t('wires.config.assetTab')" name="asset">
        <div class="asset-config__driver">
          <span class="asset-config__driver-label">{{ $t('wires.config.driverSelect') }}</span>
          <el-select
            :model-value="driverPid"
            size="small"
            class="asset-config__driver-select"
            :placeholder="$t('wires.config.driverNone')"
            @update:model-value="onDriverChange"
          >
            <el-option
              v-for="driver in drivers"
              :key="driver.pid"
              :label="driver.pid"
              :value="driver.pid"
            />
          </el-select>
        </div>

        <el-form
          v-if="assetParameters.length > 0"
          label-position="top"
          size="small"
          @submit.prevent
        >
          <el-form-item
            v-for="parameter in assetParameters"
            :key="parameter.id"
            :required="parameter.required"
            :error="assetFieldError(parameter)"
          >
            <template #label>
              <span class="asset-config__param" :title="assetFieldDescription(parameter)">
                {{ assetFieldLabel(parameter) }}
              </span>
            </template>
            <ValidationInput
              :param="parameter"
              :model-value="assetValues[parameter.id] ?? ''"
              :invalid="assetFieldError(parameter) !== ''"
              @update:model-value="assetValues[parameter.id] = $event"
            />
          </el-form-item>
        </el-form>

        <div class="asset-config__channels">
          <div class="asset-config__channels-header">
            <span class="asset-config__channels-title">
              {{ $t('driversAssets.channels.title', { count: rows.length, driver: driverLabel }) }}
            </span>
            <span class="asset-config__spacer" />
            <el-button type="primary" size="small" plain @click="openAddChannel">
              {{ $t('driversAssets.channels.new') }}
            </el-button>
            <el-button size="small" plain :disabled="selectedRow < 0" @click="removeChannel">
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
              :min-width="column.base ? 90 : 110"
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
            <el-button type="primary" size="small" @click="applyAsset">
              {{ $t('wires.config.apply') }}
            </el-button>
            <el-button size="small" @click="resetAsset">
              {{ $t('driversAssets.actions.reset') }}
            </el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="driverTabLabel" name="driver">
        <p v-if="driverPid === ''" class="asset-config__empty">
          {{ $t('wires.config.driverNone') }}
        </p>
        <template v-else>
          <p v-if="driverParameters.length === 0" class="asset-config__empty">
            {{ $t('driversAssets.detail.noFields') }}
          </p>
          <el-form v-else label-position="top" size="small" @submit.prevent>
            <el-form-item
              v-for="parameter in driverParameters"
              :key="parameter.id"
              :required="parameter.required"
              :error="driverFieldError(parameter)"
              :label="parameter.name || parameter.id"
            >
              <ValidationInput
                :param="parameter"
                :model-value="driverValues[parameter.id] ?? ''"
                :invalid="driverFieldError(parameter) !== ''"
                @update:model-value="driverValues[parameter.id] = $event"
              />
            </el-form-item>
          </el-form>
          <div class="asset-config__actions">
            <el-button
              type="primary"
              size="small"
              :disabled="!driverDirty || !driverValid"
              @click="applyDriver"
            >
              {{ $t('wires.config.apply') }}
            </el-button>
            <el-button size="small" :disabled="!driverDirty" @click="resetDriver">
              {{ $t('driversAssets.actions.reset') }}
            </el-button>
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="showAddChannel"
      :title="$t('driversAssets.channels.newTitle')"
      width="380px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top" size="small" @submit.prevent="addChannel">
        <el-form-item
          :label="$t('driversAssets.channels.nameLabel')"
          required
          :error="newChannelError"
        >
          <el-input v-model="newChannelName" autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showAddChannel = false">{{
          $t('common.cancel')
        }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="newChannelError !== ''"
          @click="addChannel"
        >
          {{ $t('driversAssets.channels.add') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.asset-config {
  height: 100%;
  overflow-y: auto;
  padding: 0 4px;
}

.asset-config__tabs {
  margin-top: -8px;
}

.asset-config__driver {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.asset-config__driver-label {
  font-size: 12px;
  color: #606266;
}

.asset-config__driver-select {
  flex: 1;
}

.asset-config__param {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}

.asset-config__channels {
  margin-top: 10px;
}

.asset-config__channels-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.asset-config__channels-title {
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
</style>
