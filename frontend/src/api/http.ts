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

// 可选 HTTP Basic（wires.admin）凭据只经环境变量注入，禁止硬编码（rules/05 安全边界）
const username = import.meta.env.VITE_WIRES_USERNAME
const password = import.meta.env.VITE_WIRES_PASSWORD
if (username !== undefined && password !== undefined) {
  http.defaults.headers.common.Authorization = `Basic ${btoa(`${username}:${password}`)}`
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
