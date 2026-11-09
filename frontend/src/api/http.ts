/**
 * axios 实例与统一响应体解包。所有 HTTP 调用必须走 src/api/（rules/05），
 * 后端 URL 一律使用 /api 相对路径（开发期由 Vite 代理转发）。
 */
import axios from 'axios'
import type { AxiosError, AxiosRequestConfig } from 'axios'
import type { ApiEnvelope } from '@/types/wires'

/** 业务错误：code 为后端 6 位错误码（rules/07） */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

/** 桌面外壳（Tauri）在页面加载前注入的一次性管理员凭据：只存在于内存，不落盘、不进 bundle（RFC 005）。 */
interface DesktopRuntimeAuth {
  username: string
  password: string
}

function desktopRuntimeAuth(): DesktopRuntimeAuth | undefined {
  if (typeof window === 'undefined') {
    return undefined
  }
  return (window as Window & { __KURA_EDGE_AUTH__?: DesktopRuntimeAuth }).__KURA_EDGE_AUTH__
}

// 凭据来源优先级：桌面外壳运行时注入 > 构建期环境变量（浏览器/开发期用法）。
// 二者都没有时不发 Authorization 头，请求由后端的 401 统一响应体接管。
// 注意用真值判断而非 undefined 判断：桌面包构建时显式传空串，用来抵消 .env.local 里的开发凭据。
const injectedAuth = desktopRuntimeAuth()
const username = injectedAuth?.username ?? import.meta.env.VITE_WIRES_USERNAME
const password = injectedAuth?.password ?? import.meta.env.VITE_WIRES_PASSWORD

/**
 * 当前会话的 HTTP Basic 认证头（`Basic ...`）。
 * 供不能走 axios 的调用方复用同一份凭据：RFC 007 的 SSE 日志流用原生 `fetch`，
 * 因为浏览器的 `EventSource` 不支持自定义请求头、带不上 Basic 凭据。
 */
export function authorizationHeader(): string | undefined {
  if (!username || !password) {
    return undefined
  }
  return `Basic ${btoa(`${username}:${password}`)}`
}

const basicAuthorization = authorizationHeader()
if (basicAuthorization) {
  http.defaults.headers.common.Authorization = basicAuthorization
}

function isEnvelope(body: unknown): body is ApiEnvelope<unknown> {
  return typeof body === 'object' && body !== null && 'code' in body && 'data' in body
}

http.interceptors.response.use(
  (response) => {
    const body: unknown = response.data
    if (isEnvelope(body)) {
      if (body.code !== 0) {
        throw new ApiError(body.code, body.message)
      }
      return body.data as never
    }
    return response
  },
  (error: AxiosError<ApiEnvelope<unknown>>) => {
    const body = error.response?.data
    if (isEnvelope(body) && body.code !== 0) {
      return Promise.reject(new ApiError(body.code, body.message))
    }
    return Promise.reject(error)
  },
)

/** 发起请求并返回统一响应体中的 data 部分 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const data: unknown = await http.request(config)
  return data as T
}
