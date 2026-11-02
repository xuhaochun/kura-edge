<script setup lang="ts">
/**
 * 新建通道弹窗（对照上游 Add new channel）：
 * 预填第一个空闲的 `Channel-N`，实时校验（空 / 含 `#/+, ` / 重名），Add 按钮在校验通过前不可用。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDriversAssetsStore } from '@/stores/driversAssets'
import { suggestChannelName } from '@/composables/useAssetChannels'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', name: string): void
}>()

const store = useDriversAssetsStore()
const { t } = useI18n()

const name = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const error = computed(() => {
  const result = store.validateName(name.value)
  if (result === '') {
    return ''
  }
  return t(`driversAssets.validation.channelName.${result}`)
})

const canSubmit = computed(() => name.value !== '' && error.value === '')

watch(visible, (open) => {
  if (open) {
    name.value = suggestChannelName(store.channelRows)
  }
})

function submit(): void {
  if (!canSubmit.value) {
    return
  }
  emit('submit', name.value)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="$t('driversAssets.channels.newTitle')"
    width="380px"
    :close-on-click-modal="false"
  >
    <el-form label-position="top" size="small" @submit.prevent="submit">
      <el-form-item :label="$t('driversAssets.channels.nameLabel')" required :error="error">
        <el-input v-model="name" autocomplete="off" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="visible = false">{{ $t('common.cancel') }}</el-button>
      <el-button type="primary" size="small" :disabled="!canSubmit" @click="submit">
        {{ $t('driversAssets.channels.add') }}
      </el-button>
    </template>
  </el-dialog>
</template>
