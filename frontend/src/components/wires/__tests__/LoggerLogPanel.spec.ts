/**
 * RFC 007 LoggerLogPanel 挂载测试：空状态、行渲染与时间格式、状态文案、清空按钮、
 * 自动滚底（含行数到达上限后继续跟随）。组合式函数 useLogStream 整体 mock，只验证面板的展示契约。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, ref, type Ref } from 'vue'
import ElementPlus from 'element-plus'
import { i18n } from '@/i18n'

const useLogStreamMock = vi.hoisted(() => vi.fn())
const clearMock = vi.hoisted(() => vi.fn())

vi.mock('@/composables/useLogStream', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/composables/useLogStream')>()
  return { ...actual, useLogStream: useLogStreamMock }
})

import LoggerLogPanel from '@/components/wires/LoggerLogPanel.vue'
import { MAX_LINES, type LogStreamState } from '@/composables/useLogStream'
import type { ComponentLogLine } from '@/api/componentLog'

const SAMPLE_LINES: ComponentLogLine[] = [
  { seq: 1, timestamp: '2026-11-03T10:00:00.123Z', text: 'Received WireEnvelope from timer-1' },
  { seq: 2, timestamp: '2026-11-03T10:00:01.000Z', text: '    TEMP : 25' },
]

/** 生成从 startSeq 起的连续日志行 */
function makeLines(count: number, startSeq = 1): ComponentLogLine[] {
  return Array.from({ length: count }, (_, index) => ({
    seq: startSeq + index,
    timestamp: '2026-11-03T10:00:00.000Z',
    text: `line ${startSeq + index}`,
  }))
}

function mountPanel(
  lines: ComponentLogLine[],
  state: LogStreamState = 'live',
): { wrapper: ReturnType<typeof mount>; lines: Ref<ComponentLogLine[]> } {
  const linesRef = ref(lines)
  useLogStreamMock.mockReturnValue({
    lines: linesRef,
    state: ref(state),
    clear: clearMock,
    stop: vi.fn(),
  })
  const wrapper = mount(LoggerLogPanel, {
    props: { pid: 'logger-1' },
    global: { plugins: [i18n, ElementPlus] },
  })
  return { wrapper, lines: linesRef }
}

/**
 * jsdom 不做布局，滚动尺寸恒为 0：手工给滚动区定义尺寸，
 * `scrollTop` 可写，才能断言「是否滚到底」。
 */
function stubScrollMetrics(element: HTMLElement, scrollHeight = 1000, clientHeight = 220): void {
  Object.defineProperty(element, 'scrollHeight', { configurable: true, get: () => scrollHeight })
  Object.defineProperty(element, 'clientHeight', { configurable: true, get: () => clientHeight })
  Object.defineProperty(element, 'scrollTop', {
    configurable: true,
    writable: true,
    value: 0,
  })
}

describe('LoggerLogPanel', () => {
  beforeEach(() => {
    clearMock.mockReset()
    useLogStreamMock.mockReset()
  })

  it('没有日志时展示等待提示', () => {
    const { wrapper } = mountPanel([])

    expect(wrapper.find('.log-panel__empty').text()).toContain('暂无日志')
    expect(wrapper.findAll('.log-panel__line')).toHaveLength(0)
  })

  it('按行渲染日志正文与 HH:mm:ss.SSS 时间', () => {
    const { wrapper } = mountPanel(SAMPLE_LINES)

    const rows = wrapper.findAll('.log-panel__line')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('.log-panel__text').text()).toBe('Received WireEnvelope from timer-1')
    expect(rows[1].text()).toContain('TEMP : 25')
    expect(rows[0].find('.log-panel__time').text()).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/)
  })

  it('状态点文案跟随流状态', () => {
    const live = mountPanel(SAMPLE_LINES, 'live').wrapper
    expect(live.find('.log-panel__state').text()).toContain('实时')

    const reconnecting = mountPanel(SAMPLE_LINES, 'reconnecting').wrapper
    expect(reconnecting.find('.log-panel__state').text()).toContain('重连中')
  })

  it('有日志时清空按钮可点并调用 clear', async () => {
    const { wrapper } = mountPanel(SAMPLE_LINES)

    const button = wrapper.find('.log-panel__header button')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')

    expect(clearMock).toHaveBeenCalledTimes(1)
  })

  it('没有日志时清空按钮禁用', () => {
    const { wrapper } = mountPanel([])

    expect(wrapper.find('.log-panel__header button').attributes('disabled')).toBeDefined()
  })

  it('新行到来时自动滚到底', async () => {
    const { wrapper, lines } = mountPanel(SAMPLE_LINES)
    await nextTick()

    const body = wrapper.find('.log-panel__body').element as HTMLElement
    stubScrollMetrics(body)

    lines.value = lines.value.concat(makeLines(1, 3))
    await nextTick()
    await nextTick()

    expect(body.scrollTop).toBe(1000)
  })

  it('行数达到 MAX_LINES 上限后新行仍然自动滚到底', async () => {
    const { wrapper, lines } = mountPanel(makeLines(MAX_LINES))
    await nextTick()

    const body = wrapper.find('.log-panel__body').element as HTMLElement
    stubScrollMetrics(body)
    // 模拟此前已跟随到底后又被程序清掉的滚动位置；行数恒定，只有新序号能触发滚底
    body.scrollTop = 0

    // 末尾追加 + 丢弃最旧：长度仍是 MAX_LINES（第 101 行到来时的真实形态）
    lines.value = lines.value.slice(1).concat(makeLines(1, MAX_LINES + 1))
    await nextTick()
    await nextTick()

    expect(lines.value).toHaveLength(MAX_LINES)
    expect(body.scrollTop).toBe(1000)
  })

  it('用户手动上滚后暂停自动滚底', async () => {
    const { wrapper, lines } = mountPanel(makeLines(MAX_LINES))
    await nextTick()

    const body = wrapper.find('.log-panel__body').element as HTMLElement
    stubScrollMetrics(body)
    body.scrollTop = 100
    await wrapper.find('.log-panel__body').trigger('scroll')

    lines.value = lines.value.concat(makeLines(1, MAX_LINES + 1))
    await nextTick()
    await nextTick()

    expect(body.scrollTop).toBe(100)
  })
})
