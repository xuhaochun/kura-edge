/**
 * Drivers and Assets REST（rules/07 登记的 /api/v1/wires/drivers|assets 与元数据端点）。
 *
 * 端点与上游的对应关系（详见 RFC 002 §5）：
 * - `GET /drivers|assets` ↔ `/wire/v1/drivers|assets/pids`（返回 `{components}`）
 * - `POST /drivers|assets` ↔ `/configuration/v2/factoryComponents`（建实例）
 * - `PUT /drivers/{pid}`、`PUT /assets/{pid}` ↔ 更新配置（资产是「删建重建」语义）
 * - `DELETE /drivers/{pid}|assets/{pid}` ↔ 删除实例（被引用时后端 409）
 */
import { request } from './http'
import type {
  AssetChannelDto,
  Ad,
  ChannelRecordDto,
  PidAndFactoryPidDto,
  PidAndFactoryPidSetDto,
  WriteChannelDto,
} from '@/types/driversAssets'

const WIRES_BASE = '/v1/wires'
const ASSETS_BASE = '/v1/assets'

/** GET /v1/wires/drivers：驱动实例列表 */
export function getDriverInfos(): Promise<PidAndFactoryPidDto[]> {
  return request<PidAndFactoryPidSetDto>({ url: `${WIRES_BASE}/drivers`, method: 'get' }).then(
    (result) => result.components ?? [],
  )
}

/** GET /v1/wires/assets：资产实例列表 */
export function getAssetInfos(): Promise<PidAndFactoryPidDto[]> {
  return request<PidAndFactoryPidSetDto>({ url: `${WIRES_BASE}/assets`, method: 'get' }).then(
    (result) => result.components ?? [],
  )
}

/** POST /v1/wires/drivers：按驱动工厂创建实例 */
export function createDriver(factoryPid: string, pid: string): Promise<void> {
  return request<unknown>({
    url: `${WIRES_BASE}/drivers`,
    method: 'post',
    data: { factoryPid, pid },
  }).then(() => undefined)
}

/** POST /v1/wires/assets：创建资产实例并绑定驱动 */
export function createAsset(pid: string, driverPid: string, description = ''): Promise<void> {
  return request<unknown>({
    url: `${WIRES_BASE}/assets`,
    method: 'post',
    data: { pid, driverPid, description },
  }).then(() => undefined)
}

/** GET /v1/wires/assets/{pid}/configuration：资产原始配置属性表 */
export function getAssetConfiguration(pid: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>({
    url: `${WIRES_BASE}/assets/${encodeURIComponent(pid)}/configuration`,
    method: 'get',
  })
}

/** PUT /v1/wires/assets/{pid}：整体替换资产配置（后端删建重建实例） */
export function putAssetConfiguration(
  pid: string,
  properties: Record<string, unknown>,
): Promise<void> {
  return request<unknown>({
    url: `${WIRES_BASE}/assets/${encodeURIComponent(pid)}`,
    method: 'put',
    data: properties,
  }).then(() => undefined)
}

/** GET /v1/wires/drivers/{pid}/configuration：驱动原始配置属性表 */
export function getDriverConfiguration(pid: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>({
    url: `${WIRES_BASE}/drivers/${encodeURIComponent(pid)}/configuration`,
    method: 'get',
  })
}

/** PUT /v1/wires/drivers/{pid}：整体替换驱动配置并重建实例 */
export function putDriverConfiguration(
  pid: string,
  properties: Record<string, unknown>,
): Promise<void> {
  return request<unknown>({
    url: `${WIRES_BASE}/drivers/${encodeURIComponent(pid)}`,
    method: 'put',
    data: properties,
  }).then(() => undefined)
}

/** DELETE /v1/wires/drivers/{pid}：删除驱动（仍有资产引用时 409003） */
export function deleteDriver(pid: string): Promise<void> {
  return request<unknown>({
    url: `${WIRES_BASE}/drivers/${encodeURIComponent(pid)}`,
    method: 'delete',
  }).then(() => undefined)
}

/** DELETE /v1/wires/assets/{pid}：删除资产（仍被线图引用时 409003） */
export function deleteAsset(pid: string): Promise<void> {
  return request<unknown>({
    url: `${WIRES_BASE}/assets/${encodeURIComponent(pid)}`,
    method: 'delete',
  }).then(() => undefined)
}

/** GET /v1/wires/assets/{pid}/channel-descriptor：基座 + 驱动合并后的通道参数定义 */
export function getAssetChannelDescriptor(pid: string): Promise<Ad[]> {
  return request<Ad[]>({
    url: `${WIRES_BASE}/assets/${encodeURIComponent(pid)}/channel-descriptor`,
    method: 'get',
  })
}

/** GET /v1/assets/{pid}：资产通道列表（配置层面，不读设备） */
export function getAssetChannels(pid: string): Promise<AssetChannelDto[]> {
  return request<AssetChannelDto[]>({
    url: `${ASSETS_BASE}/${encodeURIComponent(pid)}`,
    method: 'get',
  })
}

/** GET /v1/assets/{pid}/_read：读全部可读通道（上游形态：通道名 → 原始值） */
export function readAllChannels(pid: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>({
    url: `${ASSETS_BASE}/${encodeURIComponent(pid)}/_read`,
    method: 'get',
  })
}

/** POST /v1/assets/{pid}/_read：读指定通道（带 status / exceptionMessage） */
export function readChannels(pid: string, channels: string[]): Promise<ChannelRecordDto[]> {
  return request<ChannelRecordDto[]>({
    url: `${ASSETS_BASE}/${encodeURIComponent(pid)}/_read`,
    method: 'post',
    data: { channels },
  })
}

/** POST /v1/assets/{pid}/_write：写通道值（返回写后记录状态） */
export function writeChannels(
  pid: string,
  channels: WriteChannelDto[],
): Promise<ChannelRecordDto[]> {
  return request<ChannelRecordDto[]>({
    url: `${ASSETS_BASE}/${encodeURIComponent(pid)}/_write`,
    method: 'post',
    data: { channels },
  })
}
