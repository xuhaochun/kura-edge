/**
 * RFC 007 组件日志 SSE 客户端单测：帧解析（正常帧 / 注释帧 / 坏帧）与 fetch 流读取。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../http'
import { openComponentLogStream, parseSseFrame } from '../componentLog'
import type { ComponentLogLine } from '../componentLog'

const fetchMock = vi.fn()

describe('parseSseFrame', () => {
  it('解析 data 行里的 JSON 日志行', () => {
    const line = parseSseFrame('data: {"seq":7,"timestamp":"2026-11-03T10:00:00Z","text":"hello"}')

    expect(line).toEqual({ seq: 7, timestamp: '2026-11-03T10:00:00Z', text: 'hello' })
    expect(line?.timestamp).toBe('2026-11-03T10:00:00Z')
  })

  it('心跳注释帧不产生日志行', () => {
    expect(parseSseFrame(': keep-alive')).toBeNull()
  })

  it('非 JSON 的坏帧不产生日志行', () => {
    expect(parseSseFrame('data: <html>proxy error</html>')).toBeNull()
  })

  it('缺少 seq/text 的帧不产生日志行', () => {
    expect(parseSseFrame('data: {"timestamp":"2026-11-03T10:00:00Z"}')).toBeNull()
  })
})

describe('openComponentLogStream', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('跨 chunk 拆帧时仍按 SSE 边界回调每一行', async () => {
    givenStreamResponse([
      'data: {"seq":1,"timestamp":"2026-11-03T10:00:00Z","text":"first"}\n\n' + 'data: {"sec',
      'ond":true}\n\n',
      'data: {"seq":2,"timestamp":"2026-11-03T10:00:01Z","text":"second"}\n\n',
    ])
    const lines: ComponentLogLine[] = []

    await openComponentLogStream(
      'logger-1',
      0,
      { onLine: (line) => lines.push(line) },
      new AbortController().signal,
    )

    expect(lines.map((line) => line.seq)).toEqual([1, 2])
    expect(lines[1].text).toBe('second')
  })

  it('按 since 发起请求并带上 SSE Accept 头', async () => {
    givenStreamResponse([])

    await openComponentLogStream('logger/1', 7, { onLine: vi.fn() }, new AbortController().signal)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/wires/graph/components/logger%2F1/log/stream?since=7')
    expect(init.headers.Accept).toBe('text/event-stream')
  })

  it('连接被拒时抛出 ApiError', async () => {
    givenErrorResponse(500)

    await expect(
      openComponentLogStream('logger-1', 0, { onLine: vi.fn() }, new AbortController().signal),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

function givenStreamResponse(chunks: string[]): void {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
  fetchMock.mockResolvedValue({ ok: true, status: 200, body })
}

function givenErrorResponse(status: number): void {
  fetchMock.mockResolvedValue({ ok: false, status, body: null })
}
