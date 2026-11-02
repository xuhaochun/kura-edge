/**
 * 图快照 REST（rules/07：/api/v1/wires/graph*）。
 *
 * 载荷形态与上游一致：GET 返回「WireGraphService 配置 + 图内各组件配置」的配置列表，
 * PUT 需要同形态的 {configs}；图 JSON（顶层只有 components / wires）藏在 WireGraphService
 * 的 `WireGraph` 字符串属性里，组件配置属性各自单独一条。
 * 配置列表 ⇄ 图 JSON 的转换在 ./wireGraphCodec（快照 JSON 字段名是兼容契约）。
 */
import { request } from './http'
import type { ComponentConfigurationDto } from '@/types/wires'

const GRAPH_BASE = '/v1/wires/graph'

/** GET /graph/snapshot：取回 WireGraphService 配置与图内全部组件配置（密码已脱敏） */
export function getSnapshot(): Promise<ComponentConfigurationDto[]> {
  return request<ComponentConfigurationDto[]>({ url: `${GRAPH_BASE}/snapshot`, method: 'get' })
}

/** PUT /graph/snapshot：整图保存（后端负责校验/差异更新/快照落库） */
export function putSnapshot(configs: ComponentConfigurationDto[]): Promise<void> {
  return request<unknown>({ url: `${GRAPH_BASE}/snapshot`, method: 'put', data: { configs } }).then(
    () => undefined,
  )
}

/** DELETE /graph：删除整图 */
export function deleteGraph(): Promise<void> {
  return request<unknown>({ url: GRAPH_BASE, method: 'delete' }).then(() => undefined)
}
