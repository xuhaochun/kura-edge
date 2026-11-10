/**
 * 组件运行日志 SSE 客户端（RFC 007）。
 *
 * 为什么用原生 `fetch` + `ReadableStream` 而不是 `EventSource`：浏览器的 `EventSource`
 * 不能携带自定义请求头，而本项目的凭据是 HTTP Basic（只在 `Authorization` 头里，
 * 见 `http.ts` 与 RFC 005 的桌面注入），所以必须手动读流并解析 SSE 帧。
 */
import { ApiError, authorizationHeader } from './http'

/** 一条组件日志行（与后端 ComponentLogLineDto 对应） */
export interface ComponentLogLine {
  /** 全局单调序号：断线重连时作为增量起点 */
  seq: number
  /** ISO-8601 时间戳 */
  timestamp: string
  /** 日志文本 */
  text: string
}

/** 流回调 */
export interface LogStreamHandlers {
  /** 收到一行日志 */
  onLine: (line: ComponentLogLine) => void
}

const STREAM_PATH = '/api/v1/wires/graph/components'

/**
 * 解析一个 SSE 帧：取 `data:` 行拼成 JSON，非法帧返回 null。
 * 注释帧（`: keep-alive`）与无 data 的帧都返回 null。
 *
 * @param frame 单个 SSE 帧（不含帧尾空行）
 * @returns 解析出的日志行，或 null
 */
export function parseSseFrame(frame: string): ComponentLogLine | null {
  const dataLines: string[] = []
  for (const rawLine of frame.split(/\r?\n/)) {
    if (rawLine.startsWith('data:')) {
      dataLines.push(rawLine.slice(5).trimStart())
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(dataLines.join('\n'))
    if (typeof parsed === 'object' && parsed !== null) {
      const candidate = parsed as Partial<ComponentLogLine>
      if (typeof candidate.seq === 'number' && typeof candidate.text === 'string') {
        return {
          seq: candidate.seq,
          timestamp: typeof candidate.timestamp === 'string' ? candidate.timestamp : '',
          text: candidate.text,
        }
      }
    }
  } catch {
    // 非 JSON 帧（例如代理注入的错误页）忽略：坏帧不该打断整个面板
  }
  return null
}

/**
 * 订阅组件的实时日志流，流结束（服务端关闭 / abort）时 resolve。
 *
 * @param pid 组件 PID
 * @param sinceSeq 已消费到的序号
 * @param handlers 行回调
 * @param signal 中止信号（取消选中 / 卸载时 abort）
 * @throws ApiError 连接被拒（401/403/500 等）
 */
export async function openComponentLogStream(
  pid: string,
  sinceSeq: number,
  handlers: LogStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  const authorization = authorizationHeader()
  if (authorization) {
    headers.Authorization = authorization
  }
  const url = `${STREAM_PATH}/${encodeURIComponent(pid)}/log/stream?since=${sinceSeq}`
  const response = await fetch(url, { headers, signal })
  if (!response.ok) {
    throw new ApiError(response.status * 1000, `日志流连接失败 (${response.status})`)
  }
  if (!response.body) {
    throw new ApiError(500002, '日志流不可读')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      return
    }
    buffer += decoder.decode(value, { stream: true })
    let separator = /\r?\n\r?\n/.exec(buffer)
    while (separator && separator.index >= 0) {
      const frame = buffer.slice(0, separator.index)
      buffer = buffer.slice(separator.index + separator[0].length)
      const line = parseSseFrame(frame)
      if (line) {
        handlers.onLine(line)
      }
      separator = /\r?\n\r?\n/.exec(buffer)
    }
  }
}
