<script setup lang="ts">
/**
 * 组件日志详情面板（RFC 007）：挂在配置表单下方，点中日志记录器后像 `tail -f` 一样
 * 实时滚动展示该组件收到的记录。
 *
 * 只保留最近 {@link MAX_LINES} 行（超过丢弃最旧），新行到来时自动滚到底；
 * 用户手动往上翻时暂停自动滚底，滚回底部后恢复。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_LINES, useLogStream } from '@/composables/useLogStream'

const props = defineProps<{ pid: string }>()

const { t } = useI18n()
const { lines, state, clear } = useLogStream(() => props.pid)

const container = ref<HTMLElement | null>(null)
const autoScroll = ref(true)

const stateText = computed(() => t(`wires.logTail.state.${state.value}`))

/** 状态到样式的映射（live 绿、reconnecting 橙、其余灰） */
const stateClass = computed(() => `log-panel__state--${state.value}`)

/**
 * 最新一行的序号 —— 自动滚底的触发信号。
 * 不能用 `lines.length`：行数到达 {@link MAX_LINES} 后长度恒定（末尾追加 + 丢弃最旧），
 * 滚动条会从第 101 行起停止跟随新数据（RFC 007 的 tail -f 行为被破坏）。
 */
const latestSeq = computed(() => lines.value[lines.value.length - 1]?.seq ?? 0)

watch(latestSeq, async () => {
  if (!autoScroll.value) {
    return
  }
  await nextTick()
  scrollToBottom()
})

watch(
  () => props.pid,
  () => {
    autoScroll.value = true
  },
)

/** 判断是否已滚到底：留 8px 容差（小数像素与行高取整） */
function onScroll(): void {
  const element = container.value
  if (!element) {
    return
  }
  autoScroll.value = element.scrollTop + element.clientHeight >= element.scrollHeight - 8
}

function scrollToBottom(): void {
  const element = container.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

/** 清空显示：同时恢复自动滚底（清空后到来的新行继续跟随，不因清空前的手动上滚而停住） */
function onClear(): void {
  clear()
  autoScroll.value = true
}

/** ISO-8601 → 本地 HH:mm:ss.SSS（解析失败时退回空串，不干扰正文） */
function formatTime(timestamp: string): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const pad = (value: number, width = 2): string => String(value).padStart(width, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`
}
</script>

<template>
  <section class="log-panel">
    <div class="log-panel__header">
      <span class="log-panel__title">{{ $t('wires.logTail.title') }}</span>
      <span class="log-panel__state" :class="stateClass">
        <span class="log-panel__dot" />
        {{ stateText }}
      </span>
      <span class="log-panel__spacer" />
      <el-button link size="small" :disabled="lines.length === 0" @click="onClear">
        {{ $t('wires.logTail.clear') }}
      </el-button>
    </div>
    <div ref="container" class="log-panel__body" @scroll="onScroll">
      <p v-if="lines.length === 0" class="log-panel__empty">{{ $t('wires.logTail.empty') }}</p>
      <div v-for="line in lines" :key="line.seq" class="log-panel__line">
        <span class="log-panel__time">{{ formatTime(line.timestamp) }}</span>
        <span class="log-panel__text">{{ line.text }}</span>
      </div>
    </div>
    <p class="log-panel__hint">{{ $t('wires.logTail.hint', { max: MAX_LINES }) }}</p>
  </section>
</template>

<style scoped>
.log-panel {
  display: flex;
  flex-direction: column;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #dcdfe6;
}

.log-panel__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.log-panel__title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.log-panel__spacer {
  flex: 1;
}

.log-panel__state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #909399;
}

.log-panel__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c0c4cc;
}

.log-panel__state--live {
  color: #67c23a;
}

.log-panel__state--live .log-panel__dot {
  background: #67c23a;
}

.log-panel__state--reconnecting {
  color: #e6a23c;
}

.log-panel__state--reconnecting .log-panel__dot {
  background: #e6a23c;
}

/* 日志区固定高度、自己滚动，避免把配置表单挤出可视区 */
.log-panel__body {
  height: 220px;
  overflow-y: auto;
  padding: 6px 8px;
  background: #1e1e1e;
  border-radius: 4px;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.log-panel__empty {
  margin: 0;
  color: #909399;
  font-family: inherit;
}

.log-panel__line {
  display: flex;
  gap: 8px;
  white-space: pre-wrap;
  word-break: break-all;
  color: #d4d4d4;
}

.log-panel__time {
  flex: 0 0 auto;
  color: #7f9db9;
}

.log-panel__text {
  flex: 1 1 auto;
}

.log-panel__hint {
  margin: 4px 0 0;
  font-size: 11px;
  color: #909399;
}
</style>
