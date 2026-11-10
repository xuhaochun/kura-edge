/**
 * 系统与热更新 REST（rules/07：`/api/v1/system/**`，全部要求 `wires.admin`）。
 *
 * 只在 Tauri 桌面外壳里真正可用：后端靠外壳注入的 `KURA_EDGE_UPDATE_DIR` / `KURA_EDGE_HOME`
 * 才知道"能热更新"，以 `java -jar` 启动时 `/update` 直接返回 409004（RFC 006）。
 */
import { request } from './http'
import type {
  BackendUpdateAccepted,
  BackendUpdateRequest,
  FeedStatus,
  SystemVersion,
} from '@/types/system'

/** GET /v1/system/version：当前后端版本、运行角色与是否具备蓝绿能力 */
export function getSystemVersion(): Promise<SystemVersion> {
  return request<SystemVersion>({ url: '/v1/system/version', method: 'get' })
}

/**
 * POST /v1/system/update：请求后端下载并安装新的 jar。
 *
 * 202 只代表受理：backend 约 1.5 s 后自行退出（单机模式）或等外壳触发蓝绿移交（PostgreSQL 模式），
 * 期间页面可能短暂不可用，外壳会在新实例就绪后自动把窗口导航过去。
 */
export function requestBackendUpdate(
  payload: BackendUpdateRequest,
): Promise<BackendUpdateAccepted> {
  return request<BackendUpdateAccepted>({
    url: '/v1/system/update',
    method: 'post',
    data: payload,
    // 下载 60 MB 的 jar 后端最多等 300 s，axios 的 15 s 默认超时会把"受理中"误判成失败
    timeout: 320000,
  })
}

/** GET /v1/system/feed：自动更新（设备定时拉清单）的当前状态 */
export function getFeedStatus(): Promise<FeedStatus> {
  return request<FeedStatus>({ url: '/v1/system/feed', method: 'get' })
}

/**
 * POST /v1/system/feed/check：立即检查一次清单，不必等下一轮轮询。
 *
 * 同步执行：发现更新的版本会当场下载并交给外壳，所以这里同样放宽超时。
 */
export function checkFeedNow(): Promise<FeedStatus> {
  return request<FeedStatus>({ url: '/v1/system/feed/check', method: 'post', timeout: 320000 })
}
