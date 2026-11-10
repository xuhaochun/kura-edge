<script setup lang="ts">
/**
 * 后端 jar 热更新弹窗（RFC 006 + RFC 007 蓝绿）。
 *
 * 交互只做一件事：填一个网址，点「开始升级」。
 * - 网址必填，只允许 http/https；
 * - sha256 / 版本号是可选的高级项：留空时后端用实际下载到的摘要，并从 URL 文件名
 *   （`kura-edge-<版本>.jar`）推导版本号；
 * - 受理后不自己刷新页面：桌面外壳会把窗口导航到新实例（蓝绿模式几乎无感），
 *   单机模式则等后端重启完再导航。这里只负责给出"已受理"的状态与失败原因。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import { checkFeedNow, getFeedStatus, getSystemVersion, requestBackendUpdate } from '@/api/system'
import type { FeedStatus, SystemVersion } from '@/types/system'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { t } = useI18n()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const url = ref('')
const sha256 = ref('')
const version = ref('')
const submitting = ref(false)
const accepted = ref(false)
const error = ref('')
const current = ref<SystemVersion | undefined>()
const feed = ref<FeedStatus | undefined>()
const checkingFeed = ref(false)

const URL_PATTERN = /^https?:\/\/\S+$/i
const SHA256_PATTERN = /^[0-9a-fA-F]{64}$/

/** 网址格式问题（空串不算错，避免刚打开就报红） */
const urlError = computed(() => {
  if (url.value === '') {
    return ''
  }
  return URL_PATTERN.test(url.value) ? '' : t('app.upgrade.invalidUrl')
})

/** sha256 填了就必须是 64 位十六进制 */
const sha256Error = computed(() => {
  if (sha256.value === '') {
    return ''
  }
  return SHA256_PATTERN.test(sha256.value) ? '' : t('app.upgrade.invalidSha256')
})

const canSubmit = computed(
  () =>
    url.value !== '' &&
    urlError.value === '' &&
    sha256Error.value === '' &&
    !submitting.value &&
    !accepted.value,
)

/**
 * 能不能"一键升到服务器最新版"：清单开着、看得到最新版本、且与当前版本不同。
 * 这条路径不给 url —— 地址与 sha256 都由发布服务器清单给出（RFC 010）。
 */
const canUpgradeToLatest = computed(
  () =>
    !submitting.value &&
    !accepted.value &&
    feed.value?.enabled === true &&
    (feed.value.latestSeenVersion ?? '') !== '' &&
    feed.value.latestSeenVersion !== current.value?.version,
)

/** 主按钮文案：优先"升级到最新版"，否则是手填网址的手动升级 */
const primaryLabel = computed(() =>
  canUpgradeToLatest.value
    ? t('app.upgrade.upgradeToLatest', { version: feed.value?.latestSeenVersion })
    : t('app.upgrade.submit'),
)

/** 主按钮是否可点 */
const canClickPrimary = computed(() => canUpgradeToLatest.value || canSubmit.value)

/** 单机模式（文件库）升级会真重启，提前把代价说清楚 */
const interruptionHint = computed(() =>
  current.value && !current.value.blueGreen ? t('app.upgrade.singleInstanceHint') : '',
)

/** 自动更新（设备定时拉清单）的说明：开着就不用来回填网址，关着才需要手动 */
const feedHint = computed(() => {
  const status = feed.value
  if (!status || !status.enabled) {
    return t('app.upgrade.feedOff')
  }
  const minutes = Math.max(1, Math.round(status.intervalMs / 60000))
  return t('app.upgrade.feedOn', {
    minutes,
    version: status.latestSeenVersion || '—',
    checked: status.lastCheckAt ? status.lastCheckAt.slice(11, 16) : '—',
  })
})

watch(
  visible,
  (open) => {
    if (!open) {
      return
    }
    url.value = ''
    sha256.value = ''
    version.value = ''
    error.value = ''
    accepted.value = false
    submitting.value = false
    void getSystemVersion()
      .then((info) => {
        current.value = info
      })
      .catch(() => {
        // 版本信息只是提示，取不到不影响升级
        current.value = undefined
      })
    feed.value = undefined
    checkingFeed.value = false
    void getFeedStatus()
      .then((status) => {
        feed.value = status
      })
      .catch(() => {
        // 自动更新状态同样是提示，取不到不影响手动升级
        feed.value = undefined
      })
  },
  { immediate: true },
)

/** 立即检查清单：发现新版本会当场交给外壳，不用等下一轮轮询 */
async function checkFeed(): Promise<void> {
  checkingFeed.value = true
  try {
    feed.value = await checkFeedNow()
  } catch (failure: unknown) {
    error.value = failure instanceof ApiError ? failure.message : String(failure)
  } finally {
    checkingFeed.value = false
  }
}

/** 主按钮：能一键升就升到服务器最新版，否则走手填网址 */
async function submitPrimary(): Promise<void> {
  if (canUpgradeToLatest.value) {
    await stageUpdate({ url: '', sha256: '', version: feed.value?.latestSeenVersion ?? '' })
    return
  }
  await submit()
}

/** 真正发请求的地方：手动升级与一键升级共用 */
async function stageUpdate(payload: {
  url: string
  sha256: string
  version: string
}): Promise<void> {
  submitting.value = true
  error.value = ''
  try {
    await requestBackendUpdate(payload)
    accepted.value = true
  } catch (failure: unknown) {
    error.value = failure instanceof ApiError ? failure.message : String(failure)
  } finally {
    submitting.value = false
  }
}

async function submit(): Promise<void> {
  if (!canSubmit.value) {
    return
  }
  await stageUpdate({
    url: url.value.trim(),
    sha256: sha256.value.trim(),
    version: version.value.trim(),
  })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="$t('app.upgrade.title')"
    width="560px"
    :close-on-click-modal="false"
    class="upgrade"
  >
    <el-alert
      v-if="accepted"
      type="success"
      :closable="false"
      show-icon
      :title="$t('app.upgrade.acceptedTitle')"
      :description="$t('app.upgrade.acceptedHint')"
    />
    <el-alert
      v-else-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="$t('app.upgrade.failedTitle')"
      :description="error"
    />

    <el-form v-if="!accepted" label-position="top" class="upgrade__form" @submit.prevent>
      <el-form-item :label="$t('app.upgrade.url')" :error="urlError" required>
        <el-input
          v-model="url"
          class="upgrade__url"
          :placeholder="$t('app.upgrade.urlPlaceholder')"
          clearable
        />
      </el-form-item>
      <p class="upgrade__hint">{{ $t('app.upgrade.urlHint') }}</p>
      <div class="upgrade__optional">
        <el-form-item :label="$t('app.upgrade.sha256')" :error="sha256Error">
          <el-input
            v-model="sha256"
            class="upgrade__sha256"
            :placeholder="$t('app.upgrade.sha256Placeholder')"
            clearable
          />
        </el-form-item>
        <el-form-item :label="$t('app.upgrade.version')">
          <el-input
            v-model="version"
            class="upgrade__version"
            :placeholder="$t('app.upgrade.versionPlaceholder')"
            clearable
          />
        </el-form-item>
      </div>
      <p v-if="feed?.url" class="upgrade__source">
        {{ $t('app.upgrade.source', { url: feed.url }) }}
      </p>
      <p class="upgrade__feed">
        {{ feedHint }}
        <el-button
          v-if="feed?.enabled"
          class="upgrade__check"
          text
          type="primary"
          size="small"
          :loading="checkingFeed"
          @click="checkFeed"
        >
          {{ $t('app.upgrade.checkNow') }}
        </el-button>
      </p>
      <p v-if="current" class="upgrade__current">
        {{ $t('app.upgrade.current', { version: current.version, role: current.role }) }}
      </p>
      <p v-if="interruptionHint" class="upgrade__warn">{{ interruptionHint }}</p>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">{{ $t('common.cancel') }}</el-button>
      <el-button
        v-if="!accepted"
        type="primary"
        :loading="submitting"
        :disabled="!canClickPrimary"
        @click="submitPrimary"
      >
        {{ primaryLabel }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.upgrade__form {
  margin-top: 12px;
}

.upgrade__hint,
.upgrade__current,
.upgrade__warn,
.upgrade__source,
.upgrade__feed {
  margin: 0 0 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.upgrade__feed {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.upgrade__check {
  padding: 0 4px;
  height: auto;
}

.upgrade__warn {
  color: #e6a23c;
}

.upgrade__optional {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
</style>
