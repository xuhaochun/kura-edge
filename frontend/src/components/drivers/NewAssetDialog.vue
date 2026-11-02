<script setup lang="ts">
/**
 * 新建资产弹窗（对照上游 New Asset modal）：
 * 填实例名 + 可选的资产描述；驱动名只读显示（取当前选中的驱动，与上游一致）。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  modelValue: boolean
  driverPid: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', payload: { pid: string; description: string }): void
}>()

const { t } = useI18n()

/** 与上游 PidTextBox 相同的 service.pid 正则 */
const PID_PATTERN = /^[\w-]+(\.[\w-]+)*$/

const pid = ref('')
const description = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const pidError = computed(() => {
  if (pid.value === '') {
    return ''
  }
  return PID_PATTERN.test(pid.value) ? '' : t('driversAssets.validation.invalidPid')
})

const canSubmit = computed(() => pid.value !== '' && pidError.value === '')

watch(visible, (open) => {
  if (open) {
    pid.value = ''
    description.value = ''
  }
})

function submit(): void {
  if (!canSubmit.value) {
    return
  }
  emit('submit', { pid: pid.value, description: description.value })
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
      <el-form-item :label="$t('driversAssets.newAsset.driver')">
        <el-input :model-value="driverPid" class="new-asset__control" readonly />
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
