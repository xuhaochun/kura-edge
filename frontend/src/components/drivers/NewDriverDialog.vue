<script setup lang="ts">
/**
 * 新建驱动弹窗（对照上游 DriversAndAssetsUi 的 New Driver modal）：
 * 选驱动工厂（下拉里是原始工厂 PID，与上游一致）+ 填实例名（上游正则 `^[\w\-]+([\.][\w\-]+)*$`）。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getMetadata } from '@/api/metadata'
import { ApiError } from '@/api/http'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', payload: { factoryPid: string; pid: string }): void
}>()

const { t } = useI18n()

/** 与上游 PidTextBox 相同的 service.pid 正则 */
const PID_PATTERN = /^[\w-]+(\.[\w-]+)*$/

const factories = ref<string[]>([])
const factoryPid = ref('')
const pid = ref('')
const loadError = ref('')

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

const canSubmit = computed(
  () => factoryPid.value !== '' && pid.value !== '' && pidError.value === '',
)

watch(visible, (open) => {
  if (!open) {
    return
  }
  pid.value = ''
  loadError.value = ''
  void getMetadata()
    .then((metadata) => {
      factories.value = (metadata.driverOCDs ?? []).map((entry) => entry.pid)
      factoryPid.value = factories.value[0] ?? ''
    })
    .catch((error: unknown) => {
      loadError.value = error instanceof ApiError ? error.message : String(error)
    })
})

function submit(): void {
  if (!canSubmit.value) {
    return
  }
  emit('submit', { factoryPid: factoryPid.value, pid: pid.value })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="$t('driversAssets.newDriver.title')"
    width="420px"
    :close-on-click-modal="false"
    @closed="pid = ''"
  >
    <p class="new-driver__intro">{{ $t('driversAssets.newDriver.intro') }}</p>
    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      class="new-driver__alert"
    />
    <el-form label-position="top" size="small">
      <el-form-item :label="$t('driversAssets.newDriver.factory')" required>
        <el-select v-model="factoryPid" class="new-driver__control">
          <el-option v-for="option in factories" :key="option" :label="option" :value="option" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$t('driversAssets.newDriver.name')" required :error="pidError">
        <el-input v-model="pid" class="new-driver__control" autocomplete="off" />
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
.new-driver__intro {
  font-size: 12px;
  color: #606266;
  margin: 0 0 8px;
}

.new-driver__alert {
  margin-bottom: 8px;
}

.new-driver__control {
  width: 100%;
}
</style>
