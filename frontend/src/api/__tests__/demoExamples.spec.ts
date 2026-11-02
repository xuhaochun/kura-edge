/**
 * examples/ 下示例快照的冒烟测试：两份示例都必须能被 api/wireGraphCodec 正常解析，
 * 防止快照字段契约演进后示例悄悄失效（示例是用户「导入 JSON」的第一入口）。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parseSnapshotFile } from '@/api/wireGraphCodec'

// vitest 以 frontend/ 为工作目录运行；jsdom 环境下 import.meta.url 不是 file: 协议，故用 cwd 拼路径
const EXAMPLES_DIR = resolve(process.cwd(), 'examples')

const DEMO_PIDS = [
  'TimerFast',
  'FifoQueue',
  'CloudPublisherOut',
  'TimerTick',
  'TimerKeyFilter',
  'LoggerSink',
]

function loadExample(name: string): unknown {
  return JSON.parse(readFileSync(resolve(EXAMPLES_DIR, name), 'utf-8')) as unknown
}

describe('examples', () => {
  it('thenParsesFullSnapshotExampleWithEveryComponentConfiguration', () => {
    const extracted = parseSnapshotFile(loadExample('demo-wires-snapshot.json'))

    expect(extracted).not.toBeNull()
    expect(extracted?.graph.components.map((component) => component.pid)).toEqual(DEMO_PIDS)
    expect(extracted?.graph.wires).toHaveLength(4)
    for (const pid of DEMO_PIDS) {
      expect(extracted?.configurationProperties[pid]?.['service.factoryPid']).toBeTruthy()
    }
  })

  it('thenParsesGraphOnlyExampleWithoutConfigurations', () => {
    const extracted = parseSnapshotFile(loadExample('demo-graph.json'))

    expect(extracted).not.toBeNull()
    expect(extracted?.graph.components).toHaveLength(DEMO_PIDS.length)
    expect(extracted?.graph.wires).toHaveLength(4)
    expect(extracted?.configurationProperties).toEqual({})
  })
})
