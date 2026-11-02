<script setup lang="ts">
/**
 * 驱动配置表单（对照上游 ConfigurableComponentUi 用于 Driver 的部分）：
 * 按驱动工厂 OCD 渲染字段；Apply 走 PUT /wires/drivers/{pid}（后端重建实例），Reset 只回填不请求。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import ValidationInput from '@/components/wires/ValidationInput.vue'
import { toFormValues, useDriversAssetsStore } from '@/stores/driversAssets'

const emit = defineEmits<{
  (e: 'error', error: unknown): void
}>()

const store = useDriversAssetsStore()
const { t } = useI18n()

const values = ref<Record<string, string>>({})
const initial = ref<Record<string, string>>({})

const parameters = computed(() => store.driverParameters)
const pid = computed(() => (store.selected?.kind === 'driver' ? store.selected.pid : ''))
const dirty = computed(() => JSON.stringify(values.value) !== JSON.stringify(initial.value))

/** 选中驱动或配置变化时重填表单（Reset 也走这里） */
watch(
  [() => store.driverProperties, parameters],
  () => {
    const form = toFormValues(store.driverProperties, parameters.value)
    values.value = { ...form }
    initial.value = { ...form }
  },
  { immediate: true, deep: true },
)

/** 校验：必填字段不能为空（数值范围校验本轮只做后端兜底） */
function fieldError(paramId: string, required: boolean): string {
  if (required && (values.value[paramId] ?? '').trim() === '') {
    return t('driversAssets.validation.required')
  }
  return ''
}

const isValid = computed(() =>
  parameters.value.every((parameter) => fieldError(parameter.id, parameter.required) === ''),
)

function apply(): void {
  if (!isValid.value) {
    ElMessage.warning(t('driversAssets.message.formInvalid'))
    return
  }
  store
    .applyDriverConfiguration(values.value)
    .then(() => ElMessage.success(t('driversAssets.message.applied')))
    .catch(emit.bind(null, 'error'))
}

function reset(): void {
  values.value = { ...initial.value }
}
</script>

<template>
  <div class="driver-config">
    <div class="driver-config__title">
      {{ $t('driversAssets.detail.driverTitle', { pid }) }}
    </div>
    <p v-if="parameters.length === 0" class="driver-config__empty">
      {{ $t('driversAssets.detail.noFields') }}
    </p>
    <el-form v-else label-position="top" size="small" @submit.prevent>
      <el-form-item
        v-for="parameter in parameters"
        :key="parameter.id"
        :required="parameter.required"
        :error="fieldError(parameter.id, parameter.required)"
        :label="parameter.name || parameter.id"
      >
        <ValidationInput
          :param="parameter"
          :model-value="values[parameter.id] ?? ''"
          :invalid="fieldError(parameter.id, parameter.required) !== ''"
          @update:model-value="values[parameter.id] = $event"
        />
      </el-form-item>
    </el-form>
    <div class="driver-config__actions">
      <el-button
        type="primary"
        size="small"
        :disabled="!dirty"
        :loading="store.saving"
        @click="apply"
      >
        {{ $t('driversAssets.actions.apply') }}
      </el-button>
      <el-button size="small" :disabled="!dirty" @click="reset">
        {{ $t('driversAssets.actions.reset') }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.driver-config__title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.driver-config__empty {
  font-size: 12px;
  color: #909399;
}

.driver-config__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
</style>
