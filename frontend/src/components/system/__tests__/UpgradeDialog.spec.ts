/**
 * UpgradeDialog 挂载测试：网址校验、只填网址也能提交、受理态与失败态。
 *
 * 回归背景：升级入口是设备现场唯一的自救手段，必须保证
 * ① 没填网址不能提交；② 非法网址当场拦住；③ 只填网址也能走（sha256/版本号由后端补齐）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const mocks = vi.hoisted(() => ({
  getSystemVersion: vi.fn(),
  requestBackendUpdate: vi.fn(),
  getFeedStatus: vi.fn(),
  checkFeedNow: vi.fn(),
}))

vi.mock('@/api/system', () => ({
  getSystemVersion: mocks.getSystemVersion,
  requestBackendUpdate: mocks.requestBackendUpdate,
  getFeedStatus: mocks.getFeedStatus,
  checkFeedNow: mocks.checkFeedNow,
}))

const { ApiError } = await import('@/api/http')
const { i18n } = await import('@/i18n')
const UpgradeDialog = (await import('@/components/system/UpgradeDialog.vue')).default

/** 挂载后的 wrapper 类型（mountDialog 是 async，用 mount 自身的返回类型避免循环引用） */
type Dialog = ReturnType<typeof mount>

const FEED_OFF = {
  enabled: false,
  url: '',
  intervalMs: 300000,
  lastCheckAt: '',
  lastCheckOk: false,
  lastError: '',
  latestSeenVersion: '',
  lastStagedVersion: '',
  lastStagedAt: '',
  failedVersion: '',
  failedAt: '',
}

const VERSION_INFO = {
  version: '1.0.3',
  jar: 'C:/kura-edge/app/kura-edge-1.0.3.jar',
  sha256: 'a'.repeat(64),
  source: 'desktop',
  role: 'leader',
  blueGreen: true,
}

describe('UpgradeDialog', () => {
  beforeEach(() => {
    mocks.getSystemVersion.mockReset().mockResolvedValue(VERSION_INFO)
    mocks.getFeedStatus.mockReset().mockResolvedValue(FEED_OFF)
    mocks.checkFeedNow.mockReset().mockResolvedValue(FEED_OFF)
    mocks.requestBackendUpdate
      .mockReset()
      .mockResolvedValue({ version: '1.0.4', jar: 'kura-edge-1.0.4.jar', sha256: 'b'.repeat(64) })
  })

  /** 挂载并等弹窗内容渲染完（el-dialog 的 body 是异步渲染的，不等就查不到输入框） */
  async function mountDialog() {
    const wrapper = mount(UpgradeDialog, {
      props: { modelValue: true },
      global: { plugins: [i18n, ElementPlus] },
      attachTo: document.body,
    })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    return wrapper
  }

  function submitButton(wrapper: Dialog) {
    return wrapper.findAll('button').at(-1)!
  }

  async function typeUrl(wrapper: Dialog, value: string) {
    await wrapper.find('.upgrade__url input').setValue(value)
  }

  it('givenEmptyUrlThenSubmitIsDisabled', async () => {
    const wrapper = await mountDialog()
    await wrapper.vm.$nextTick()

    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    expect(mocks.requestBackendUpdate).not.toHaveBeenCalled()
  })

  it('givenNonHttpUrlThenSubmitIsRejected', async () => {
    const wrapper = await mountDialog()
    await typeUrl(wrapper, 'file:///tmp/kura-edge-1.0.4.jar')

    // el-form-item 的 error 是 props 上的常驻值，DOM 里要等过渡结束才出现
    const urlItem = wrapper.findAllComponents({ name: 'ElFormItem' })[0]!
    expect(urlItem.props('error')).toBe(i18n.global.t('app.upgrade.invalidUrl'))
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('givenOnlyUrlWhenSubmittingThenAcceptedAndRequestUsesEmptyOptionals', async () => {
    const wrapper = await mountDialog()
    await typeUrl(wrapper, 'http://192.168.31.40:8000/kura-edge-1.0.4.jar')

    await submitButton(wrapper).trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(mocks.requestBackendUpdate).toHaveBeenCalledWith({
      url: 'http://192.168.31.40:8000/kura-edge-1.0.4.jar',
      sha256: '',
      version: '',
    })
    expect(wrapper.text()).toContain(i18n.global.t('app.upgrade.acceptedTitle'))
  })

  it('givenBackendRejectionThenShowsServerMessage', async () => {
    mocks.requestBackendUpdate.mockRejectedValue(
      new ApiError(
        409004,
        '当前不是桌面运行时（缺少 KURA_EDGE_UPDATE_DIR / KURA_EDGE_HOME 环境变量），无法热更新',
      ),
    )
    const wrapper = await mountDialog()
    await typeUrl(wrapper, 'http://192.168.31.40:8000/kura-edge-1.0.4.jar')

    await submitButton(wrapper).trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('无法热更新')
    expect(wrapper.text()).not.toContain(i18n.global.t('app.upgrade.acceptedTitle'))
  })

  it('givenNoFeedUrlThenExplainsManualOnly', async () => {
    const wrapper = await mountDialog()

    expect(wrapper.text()).toContain(i18n.global.t('app.upgrade.feedOff'))
    expect(wrapper.text()).not.toContain(i18n.global.t('app.upgrade.checkNow'))
  })

  it('givenFeedEnabledThenShowsCadenceAndChecksOnDemand', async () => {
    mocks.getFeedStatus.mockResolvedValue({
      ...FEED_OFF,
      enabled: true,
      url: 'http://server/releases/feed.json',
      latestSeenVersion: '1.0.4',
      lastCheckAt: '2026-11-10T10:34:24Z',
    })
    mocks.checkFeedNow.mockResolvedValue({
      ...FEED_OFF,
      enabled: true,
      latestSeenVersion: '1.0.5',
      lastCheckAt: '2026-11-10T11:00:00Z',
    })
    const wrapper = await mountDialog()

    expect(wrapper.text()).toContain('5')
    const check = wrapper
      .findAll('button')
      .find((button) => button.text() === i18n.global.t('app.upgrade.checkNow'))
    expect(check).toBeDefined()
    await check!.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(mocks.checkFeedNow).toHaveBeenCalledTimes(1)
  })

  it('givenNewerVersionOnTheReleaseServerThenUpgradesWithOneClick', async () => {
    mocks.getFeedStatus.mockResolvedValue({
      ...FEED_OFF,
      enabled: true,
      url: 'http://server:8081/feed.json',
      latestSeenVersion: '1.0.4',
      lastCheckAt: '2026-11-10T10:34:24Z',
    })
    const wrapper = await mountDialog()

    // 主按钮直接是"升级到 1.0.4"，不需要再填网址
    const primary = wrapper.findAll('button').at(-1)!
    expect(primary.text()).toContain('1.0.4')
    await primary.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // url 留空 => 后端去发布服务器清单里取地址与 sha256（RFC 010）
    expect(mocks.requestBackendUpdate).toHaveBeenCalledWith({
      url: '',
      sha256: '',
      version: '1.0.4',
    })
    expect(wrapper.text()).toContain(i18n.global.t('app.upgrade.acceptedTitle'))
  })

  it('givenSingleInstanceBackendThenWarnsAboutInterruption', async () => {
    mocks.getSystemVersion.mockResolvedValue({ ...VERSION_INFO, blueGreen: false })
    const wrapper = await mountDialog()

    expect(wrapper.text()).toContain(i18n.global.t('app.upgrade.singleInstanceHint'))
  })
})
