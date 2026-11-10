/**
 * RFC 007 useLogStream 单测：行追加、100 行截断、序号去重、清空、断线重连与卸载中止。
 * 网络层（openComponentLogStream）整体 mock，只验证组合式函数的状态机。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'

const openComponentLogStream = vi.hoisted(() => vi.fn())

vi.mock('@/api/componentLog', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/componentLog')>()
  return { ...actual, openComponentLogStream }
})

import { MAX_LINES, RECONNECT_DELAYS, useLogStream } from '@/composables/useLogStream'
import type { ComponentLogLine } from '@/api/componentLog'

const Host = defineComponent({
  props: { pid: { type: String, required: true } },
  setup(props) {
    return { stream: useLogStream(() => props.pid) }
  },
  template: '<div />',
})

type HostVm = { stream: ReturnType<typeof useLogStream> }

function line(seq: number): ComponentLogLine {
  return {
    seq,
    timestamp: `2026-11-03T10:00:00.${String(seq).padStart(3, '0')}Z`,
    text: `line-${seq}`,
  }
}

/** 模拟一条永不结束的流：回调若干行后挂起，避免触发重连 */
function givenStreamPushing(lines: ComponentLogLine[]): void {
  openComponentLogStream.mockImplementation(
    async (
      _pid: string,
      _since: number,
      handlers: { onLine: (line: ComponentLogLine) => void },
    ) => {
      lines.forEach(handlers.onLine)
      return new Promise<void>(() => {})
    },
  )
}

function mountHost(pid = 'logger-1') {
  return mount(Host, { props: { pid } })
}

describe('useLogStream', () => {
  beforeEach(() => {
    openComponentLogStream.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('把流推送的行按序追加并进入 live 状态', async () => {
    givenStreamPushing([line(1), line(2)])
    const wrapper = mountHost()

    await vi.waitFor(() => {
      expect((wrapper.vm as unknown as HostVm).stream.lines.value.map((item) => item.seq)).toEqual([
        1, 2,
      ])
    })
    expect((wrapper.vm as unknown as HostVm).stream.state.value).toBe('live')
  })

  it('超过 100 行时丢弃最旧的行', async () => {
    givenStreamPushing(Array.from({ length: MAX_LINES + 5 }, (_, index) => line(index + 1)))
    const wrapper = mountHost()

    await vi.waitFor(() => {
      expect((wrapper.vm as unknown as HostVm).stream.lines.value).toHaveLength(MAX_LINES)
    })
    const seqs = (wrapper.vm as unknown as HostVm).stream.lines.value.map((item) => item.seq)
    expect(seqs[0]).toBe(6)
    expect(seqs[seqs.length - 1]).toBe(MAX_LINES + 5)
  })

  it('重复序号不重复显示', async () => {
    givenStreamPushing([line(1), line(2), line(2), line(1)])
    const wrapper = mountHost()

    await vi.waitFor(() => {
      expect((wrapper.vm as unknown as HostVm).stream.lines.value.map((item) => item.seq)).toEqual([
        1, 2,
      ])
    })
  })

  it('clear 清空显示但后续新行继续追加', async () => {
    givenStreamPushing([line(1), line(2)])
    const wrapper = mountHost()
    const stream = (wrapper.vm as unknown as HostVm).stream
    await vi.waitFor(() => expect(stream.lines.value).toHaveLength(2))

    stream.clear()
    openComponentLogStream.mock.calls[0][2].onLine(line(3))

    await vi.waitFor(() => {
      expect(stream.lines.value.map((item) => item.seq)).toEqual([3])
    })
  })

  it('断线后按退避序列自动重连', async () => {
    vi.useFakeTimers()
    openComponentLogStream
      .mockRejectedValueOnce(new Error('connection reset'))
      .mockImplementation(
        async (
          _pid: string,
          _since: number,
          handlers: { onLine: (line: ComponentLogLine) => void },
        ) => {
          handlers.onLine(line(1))
          return new Promise<void>(() => {})
        },
      )
    const wrapper = mountHost()

    await vi.waitFor(() => {
      expect((wrapper.vm as unknown as HostVm).stream.state.value).toBe('reconnecting')
    })
    await vi.advanceTimersByTimeAsync(RECONNECT_DELAYS[0])

    await vi.waitFor(() => {
      expect((wrapper.vm as unknown as HostVm).stream.lines.value.map((item) => item.seq)).toEqual([
        1,
      ])
    })
    expect(openComponentLogStream).toHaveBeenCalledTimes(2)
  })

  it('卸载时中止进行中的连接', async () => {
    let signal: AbortSignal | undefined
    openComponentLogStream.mockImplementation(
      async (_pid: string, _since: number, _handlers: unknown, abortSignal: AbortSignal) => {
        signal = abortSignal
        return new Promise<void>(() => {})
      },
    )
    const wrapper = mountHost()
    await vi.waitFor(() => expect(signal).toBeDefined())

    wrapper.unmount()

    expect(signal?.aborted).toBe(true)
  })
})
