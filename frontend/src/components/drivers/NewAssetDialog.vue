<script setup lang="ts">
/**
 * 新建资产弹窗（对照上游 New Asset modal）：
 * 填实例名 + 可选的资产描述，并选择该资产的驱动实例。
 *
 * 驱动下拉默认取当前上下文（选中 Driver 时就是它；选中 Asset 时是该资产所属驱动），
 * 允许改选 —— 上游「New Asset 只在选中驱动时可用」的做法在多资产场景下会把用户堵死：
 * 选中 Driver 行点新建时弹窗里驱动为空、点「应用」静默失败（见 issue 反馈）。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DriverInfo } from '@/types/driversAssets'

const props = defineProps<{
  modelValue: boolean
  drivers: DriverInfo[]
  defaultDriverPid: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', payload: { pid: string; driverPid: string; description: string }): void
}>()

const { t } = useI18n()

/** 与上游 PidTextBox 相同的 service.pid 正则 */
const PID_PATTERN = /^[\w-]+(\.[\w-]+)*$/

const pid = ref('')
const driverPid = ref('')
const description = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

/** 选中的驱动是否存在（默认值可能来自已被删除的驱动） */
const defaultDriver = computed<string>(() => {
  const exists = props.drivers.some((driver) => driver.pid === props.defaultDriverPid)
  return exists ? props.defaultDriverPid : ''
})

const pidError = computed(() => {
  if (pid.value === '') {
    return ''
  }
  return PID_PATTERN.test(pid.value) ? '' : t('driversAssets.validation.invalidPid')
})

const driverError = computed(() =>
  driverPid.value === '' && pid.value !== '' ? t('driversAssets.validation.driverRequired') : '',
)

const canSubmit = computed(
  () => pid.value !== '' && driverPid.value !== '' && pidError.value === '',
)

watch(visible, (open) => {
  if (open) {
    pid.value = ''
    description.value = ''
    driverPid.value = defaultDriver.value || (props.drivers[0]?.pid ?? '')
  }
})

function submit(): void {
  if (!canSubmit.value) {
    return
  }
  emit('submit', { pid: pid.value, driverPid: driverPid.value, description: description.value })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="$t('driversAssets.newAsset.title')"
    width="420px"
    :close-on-click-modal="false"
  >
    <p class="new-asset__intro">{{ $t('driversAssets.newAsset.intro') }}</p>
    <el-form label-position="top" size="small">
      <el-form-item :label="$t('driversAssets.newAsset.name')" required :error="pidError">
        <el-input v-model="pid" class="new-asset__control" autocomplete="off" />
      </el-form-item>
      <el-form-item :label="$t('driversAssets.newAsset.driver')" required :error="driverError">
        <el-select
          v-model="driverPid"
          class="new-asset__control"
          :placeholder="$t('driversAssets.newAsset.driverPlaceholder')"
          filterable
        >
          <el-option
            v-for="driver in drivers"
            :key="driver.pid"
            :label="driver.pid"
            :value="driver.pid"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="$t('driversAssets.newAsset.description')">
        <el-input v-model="description" class="new-asset__control" autocomplete="off" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="visible = false">{{ $t('common.cancel') }}</el-button>
      <el-button type="primary" size="small" :disabled="!canSubmit" @click="submit">
        {{ $t('driversAssets.actions.apply') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.new-asset__intro {
  font-size: 12px;
  color: #606266;
  margin: 0 0 8px;
}

.new-asset__control {
  width: 100%;
}
</style>
