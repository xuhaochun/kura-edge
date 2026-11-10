/**
 * 组件日志 tail -f 组合式（RFC 007）：订阅 SSE 流、把行追加到本地列表并截断到
 * {@link MAX_LINES} 行，断线时按指数退避自动重连。
 *
 * 只保留最近 100 行是硬需求：第 101 行到来时最旧的一行被移除（等价 `tail -n 100 -f`）。
 * 序号去重防止重连窗口里重复显示同一行。
 */
import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { openComponentLogStream, type ComponentLogLine } from '@/api/componentLog'

/** 面板最多保留的行数（超过丢弃最旧） */
export const MAX_LINES = 100

/** 断线重连退避序列（毫秒），用尽后保持最后一档 */
export const RECONNECT_DELAYS = [1_000, 2_000, 4_000, 8_000, 10_000]

/** 流状态：idle = 未订阅；live = 实时；reconnecting = 断线重连中；stopped = 已停止 */
export type LogStreamState = 'idle' | 'live' | 'reconnecting' | 'stopped'

/** 中文注释：这是给测试与组件共用的返回类型 */
export interface LogStream {
  lines: Ref<ComponentLogLine[]>
  state: Ref<LogStreamState>
  /** 清空当前显示（保留游标，新日志继续追加） */
  clear: () => void
  /** 停止订阅并断开连接 */
  stop: () => void
}

/**
 * 订阅一个组件的日志流；`pid` 变化时自动切换（清空 + 重连），作用域销毁时断开。
 *
 * @param pid 组件 PID 的 getter（未选中时返回 undefined）
 * @returns 行列表、状态与操作函数
 */
export function useLogStream(pid: () => string | undefined): LogStream {
  const lines = ref<ComponentLogLine[]>([])
  const state = ref<LogStreamState>('idle')

  let controller: AbortController | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let active = false
  let attempt = 0
  let lastSeq = 0

  function push(line: ComponentLogLine): void {
    if (line.seq <= lastSeq) {
      return
    }
    lastSeq = line.seq
    const next = lines.value.concat(line)
    lines.value = next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next
  }

  function clearReconnectTimer(): void {
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function scheduleReconnect(): void {
    if (!active) {
      return
    }
    state.value = 'reconnecting'
    const delay = RECONNECT_DELAYS[Math.min(attempt, RECONNECT_DELAYS.length - 1)]
    attempt += 1
    clearReconnectTimer()
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void connect()
    }, delay)
  }

  async function connect(): Promise<void> {
    const target = pid()
    if (!active || !target) {
      return
    }
    controller?.abort()
    controller = new AbortController()
    const signal = controller.signal
    state.value = attempt === 0 ? 'live' : 'reconnecting'
    try {
      state.value = 'live'
      await openComponentLogStream(target, lastSeq, { onLine: push }, signal)
      // 服务端正常关闭（30 分钟超时）或代理断开：进入重连
      scheduleReconnect()
    } catch {
      if (!active || signal.aborted) {
        return
      }
      scheduleReconnect()
    }
  }

  function stop(): void {
    active = false
    clearReconnectTimer()
    controller?.abort()
    controller = null
    state.value = lines.value.length > 0 ? 'stopped' : 'idle'
  }

  function start(): void {
    active = true
    attempt = 0
    void connect()
  }

  function clear(): void {
    lines.value = []
  }

  // 选中组件变化：断开旧流、清空旧日志、从头订阅新组件
  watch(
    pid,
    () => {
      stop()
      lines.value = []
      lastSeq = 0
      attempt = 0
      start()
    },
    { immediate: true },
  )

  onScopeDispose(stop)

  return { lines, state, clear, stop }
}
