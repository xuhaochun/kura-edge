import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 凭据注入（RFC 005）：桌面外壳在页面加载前把一次性口令写进 window.__KURA_EDGE_AUTH__，
 * 优先级高于构建期 VITE_WIRES_*；两者都没有时不发 Authorization 头。
 */
describe('http 客户端凭据注入', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.unstubAllEnvs()
    delete (window as Window & { __KURA_EDGE_AUTH__?: unknown }).__KURA_EDGE_AUTH__
  })

  it('桌面外壳注入凭据时使用运行时口令', async () => {
    givenDesktopAuth({ username: 'admin', password: 'runtime-secret' })

    const { http } = await import('../http')

    expect(http.defaults.headers.common.Authorization).toBe(`Basic ${btoa('admin:runtime-secret')}`)
  })

  it('只有构建期凭据时使用构建期口令（开发/浏览器场景）', async () => {
    givenBuildTimeCredentials('build-user', 'build-pass')

    const { http } = await import('../http')

    expect(http.defaults.headers.common.Authorization).toBe(
      `Basic ${btoa('build-user:build-pass')}`,
    )
  })

  it('没有运行时注入也没有构建期变量时不带 Authorization 头', async () => {
    givenBuildTimeCredentials('', '')

    const { http } = await import('../http')

    expect(http.defaults.headers.common.Authorization).toBeUndefined()
  })

  it('运行时凭据优先于构建期环境变量', async () => {
    givenBuildTimeCredentials('build-user', 'build-pass')
    givenDesktopAuth({ username: 'admin', password: 'runtime-secret' })

    const { http } = await import('../http')

    expect(http.defaults.headers.common.Authorization).toBe(`Basic ${btoa('admin:runtime-secret')}`)
  })
})

function givenDesktopAuth(auth: { username: string; password: string }): void {
  ;(window as Window & { __KURA_EDGE_AUTH__?: typeof auth }).__KURA_EDGE_AUTH__ = auth
}

function givenBuildTimeCredentials(username: string, password: string): void {
  vi.stubEnv('VITE_WIRES_USERNAME', username)
  vi.stubEnv('VITE_WIRES_PASSWORD', password)
}
