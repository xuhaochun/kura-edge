/** WireComponentNode 挂载测试：端口 handle 数量、选中高亮、删除联动删边 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// jsdom 下脱离 VueFlow 上下文挂载：stub Handle，仅保留需要断言的接口
vi.mock('@vue-flow/core', () => ({
  Handle: {
    name: 'Handle',
    props: ['type', 'position', 'id', 'style'],
    template: '<div class="handle-stub" :data-type="type" :data-handle-id="id"></div>',
  },
  Position: { Left: 'left', Right: 'right' },
}))

const { useWiresGraphStore } = await import('@/stores/wiresGraph')
const WireComponentNode = (await import('@/components/wires/WireComponentNode.vue')).default
const { i18n } = await import('@/i18n')

describe('WireComponentNode', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountNode(overrides: Record<string, unknown> = {}) {
    return mount(WireComponentNode, {
      props: {
        id: 'timer1',
        data: {
          pid: 'timer1',
          factoryPid: 'org.eclipse.kura.wires.Timer',
          inputPortCount: 2,
          outputPortCount: 1,
        },
        selected: false,
        ...overrides,
      },
      global: { plugins: [i18n] },
    })
  }

  it('thenRendersHandlePerPort', () => {
    const wrapper = mountNode()
    expect(wrapper.findAll('.handle-stub[data-type="target"]')).toHaveLength(2)
    expect(wrapper.findAll('.handle-stub[data-type="source"]')).toHaveLength(1)
    expect(wrapper.find('.handle-stub[data-handle-id="in0"]').exists()).toBe(true)
    expect(wrapper.find('.handle-stub[data-handle-id="out0"]').exists()).toBe(true)
  })

  it('thenShowsChineseLabelFromMetadataText', () => {
    const wrapper = mountNode()
    expect(wrapper.find('.wire-node__label').text()).toBe('定时器')
  })

  it('thenHighlightsWhenSelected', () => {
    const wrapper = mountNode({ selected: true })
    expect(wrapper.find('.wire-node--selected').exists()).toBe(true)
  })

  it('thenSelectsStoreOnNodeClick', async () => {
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'timer1',
      factoryPid: 'org.eclipse.kura.wires.Timer',
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })
    const wrapper = mountNode()
    await wrapper.find('.wire-node').trigger('click')
    expect(store.selectedPid).toBe('timer1')
  })

  it('thenDeleteCascadesWireRemoval', async () => {
    const store = useWiresGraphStore()
    store.addComponent({
      pid: 'timer1',
      factoryPid: 'org.eclipse.kura.wires.Timer',
      inputPortCount: 0,
      outputPortCount: 1,
      position: { x: 0, y: 0 },
    })
    store.addComponent({
      pid: 'logger1',
      factoryPid: 'org.eclipse.kura.wires.Logger',
      inputPortCount: 1,
      outputPortCount: 0,
      position: { x: 200, y: 0 },
    })
    store.addWire({ emitterPid: 'timer1', emitterPort: 0, receiverPid: 'logger1', receiverPort: 0 })

    const wrapper = mountNode()
    await wrapper.find('.wire-node__delete').trigger('click')
    expect(store.components).toHaveLength(1)
    expect(store.components[0]?.pid).toBe('logger1')
    expect(store.wires).toHaveLength(0)
  })
})
