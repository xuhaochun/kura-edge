<script setup lang="ts">
/**
 * 配置字段输入控件（对照上游 ValidationInputCell）：
 * 按 metatype 的字段类型渲染控件 —— BOOLEAN 下拉、带 options 的下拉、
 * PASSWORD 密文输入、其余走文本输入；校验失败时置 --invalid 修饰类。
 *
 * 表单值一律以字符串承载（与上游 metatype 渲染一致），由父层通过 v-model 绑定；
 * 类型转换在应用时由 useGraphValidation.toPropertyScalar 完成。
 */
import { computed } from 'vue'
import type { ConfigParameter } from '@/types/wires'
import { useMetadataText } from '@/composables/useMetadataText'

const props = defineProps<{
  /** 字段定义（来自 GET /metadata 的 properties 项） */
  param: ConfigParameter
  /** 当前表单值（字符串承载） */
  modelValue: string
  /** 该字段是否校验失败 */
  invalid: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const BOOLEAN_OPTIONS = ['true', 'false']

const { optionLabel } = useMetadataText()

/** 选项下拉项：label 走 i18n（键为后端 metatype 标签），value 保持原 token 不变 */
const choices = computed(() =>
  Object.entries(props.param.options ?? {}).map(([value, label]) => ({
    value,
    label: optionLabel(label),
  })),
)

/** 数值字段的提示文案：有上下界时显示区间，否则显示单边约束 */
function numericPlaceholder(): string {
  if (props.param.min && props.param.max) {
    return `${props.param.min} ~ ${props.param.max}`
  }
  return props.param.min || props.param.max || ''
}

function update(value: string): void {
  emit('update:modelValue', value)
}
</script>

<template>
  <el-select
    v-if="param.type === 'BOOLEAN'"
    class="config-area__input"
    :class="{ 'config-area__input--invalid': invalid }"
    :model-value="modelValue"
    @update:model-value="update"
  >
    <el-option v-for="option in BOOLEAN_OPTIONS" :key="option" :label="option" :value="option" />
  </el-select>
  <el-select
    v-else-if="param.options"
    class="config-area__input"
    :class="{ 'config-area__input--invalid': invalid }"
    :model-value="modelValue"
    clearable
    @update:model-value="update"
  >
    <el-option
      v-for="choice in choices"
      :key="choice.value"
      :label="choice.label"
      :value="choice.value"
    />
  </el-select>
  <el-input
    v-else-if="param.type === 'PASSWORD'"
    class="config-area__input"
    :class="{ 'config-area__input--invalid': invalid }"
    :model-value="modelValue"
    type="password"
    show-password
    autocomplete="new-password"
    @update:model-value="update"
  />
  <el-input
    v-else
    class="config-area__input"
    :class="{ 'config-area__input--invalid': invalid }"
    :model-value="modelValue"
    :placeholder="numericPlaceholder()"
    @update:model-value="update"
  />
</template>

<style scoped>
.config-area__input--invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #f56c6c inset;
}
</style>
