/**
 * 元数据与组件配置 REST（rules/07：/api/v1/wires/metadata*、/configs*、/drivers、/assets）。
 * 载荷形态对照上游 DTO：元数据为 { wireComponentDefinitions, ... }；
 * PID 集合为 { pids }；组件配置为 {pid, properties:{key:{type,value}}}。
 */
import { request } from './http'
import type {
  ComponentConfigurationDto,
  PidAndFactoryPidDto,
  PidSetDto,
  WiresMetadata,
} from '@/types/wires'

/** GET /metadata：组件定义（前端面板与配置表单的数据源） */
export function getMetadata(): Promise<WiresMetadata> {
  return request<WiresMetadata>({ url: '/v1/wires/metadata', method: 'get' })
}

/** GET /metadata/components/factory-pids：可用组件工厂 PID */
export function getFactoryPids(): Promise<string[]> {
  return request<PidSetDto>({
    url: '/v1/wires/metadata/components/factory-pids',
    method: 'get',
  }).then((result) => result.pids ?? [])
}

/** GET /drivers：Driver 实例列表 */
export function getDrivers(): Promise<PidAndFactoryPidDto[]> {
  return request<PidAndFactoryPidDto[]>({ url: '/v1/wires/drivers', method: 'get' })
}

/** GET /assets：Asset 实例列表 */
export function getAssets(): Promise<PidAndFactoryPidDto[]> {
  return request<PidAndFactoryPidDto[]>({ url: '/v1/wires/assets', method: 'get' })
}

/** POST /configs/by-pids：按 pid 批量取配置（密码已脱敏） */
export function getConfigsByPids(pids: string[]): Promise<ComponentConfigurationDto[]> {
  return request<ComponentConfigurationDto[]>({
    url: '/v1/wires/configs/by-pids',
    method: 'post',
    data: { pids },
  })
}

/** PUT /configs：批量更新组件配置（载荷同样是 {configs} 形态） */
export function putConfigs(configs: ComponentConfigurationDto[]): Promise<void> {
  return request<unknown>({ url: '/v1/wires/configs', method: 'put', data: { configs } }).then(
    () => undefined,
  )
}

/** DELETE /configs/by-pids：批量删除配置（被图引用的会被后端 409 拒绝） */
export function deleteConfigsByPids(pids: string[]): Promise<void> {
  return request<unknown>({
    url: '/v1/wires/configs/by-pids',
    method: 'delete',
    data: { pids },
  }).then(() => undefined)
}
