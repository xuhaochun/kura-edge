/**
 * 快照编排（对照上游 WiresRPC + WiregraphDownloadModal）：
 * 加载 → GET /graph/snapshot → 解析 WireGraph 属性与组件配置条目 → store.loadFromSnapshot；
 * 保存 → store.toSnapshot() + 组件状态 → 装配配置列表 → PUT /graph/snapshot；
 * 导出 JSON 下载（就是上游图 JSON）；导入 JSON（图 JSON 或 {configs:[...]} 整份快照，仅本地并标记 dirty）；
 * 新建组件。组件/视图层不得直接持有快照装配逻辑；编解码在 api/wireGraphCodec。
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import { useMetadataStore } from '@/stores/metadata'
import { getSnapshot, putSnapshot, deleteGraph } from '@/api/wireGraph'
import { getConfigsByPids } from '@/api/metadata'
import {
  extractGraphSnapshot,
  flattenComponentProperties,
  parseSnapshotFile,
  toSnapshotConfigurations,
} from '@/api/wireGraphCodec'
import { hasCycle } from '@/composables/useGraphValidation'
import type { Point } from '@/composables/canvas'
import type { ComponentDescriptor, WireGraphSnapshot } from '@/types/wires'

function errMsg(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function useSnapshot() {
  const store = useWiresGraphStore()
  const metadata = useMetadataStore()
  const { t } = useI18n()
  const loading = ref(false)
  const saving = ref(false)

  /** GET /graph/snapshot → 解析图 JSON 与组件配置条目 → 重建画布（对照上游 refreshWires） */
  async function load(): Promise<void> {
    loading.value = true
    try {
      const extracted = extractGraphSnapshot(await getSnapshot())
      if (!extracted) {
        ElMessage.error(t('wires.messages.invalidSnapshot'))
        return
      }
      store.loadFromSnapshot(extracted.graph, extracted.configurationProperties)
      ElMessage.success(t('wires.messages.loadSuccess'))
    } catch (error) {
      ElMessage.error(t('wires.messages.loadFailed', { msg: errMsg(error) }))
    } finally {
      loading.value = false
    }
  }

  /** 缺 factoryPid 的组件 pid：这类组件无法装配配置条目，保存必然失败 */
  function componentsWithoutFactoryPid(): string[] {
    return store.components
      .filter((component) => !component.factoryPid)
      .map((component) => component.pid)
  }

  /** 保存前兜底环检测（建线时已逐条校验，此处防线快照整体仍须无环） */
  async function save(): Promise<void> {
    if (
      hasCycle(
        store.wires.map((wire) => ({ emitterPid: wire.emitterPid, receiverPid: wire.receiverPid })),
      )
    ) {
      ElMessage.error(t('wires.validation.cycle'))
      return
    }
    const orphans = componentsWithoutFactoryPid()
    if (orphans.length > 0) {
      ElMessage.error(t('wires.messages.factoryPidMissingPids', { pids: orphans.join(', ') }))
      return
    }
    saving.value = true
    try {
      const configs = toSnapshotConfigurations(
        store.toSnapshot(),
        store.components,
        metadata.descriptorByFactoryPid,
      )
      await putSnapshot(configs)
      store.markSaved()
      ElMessage.success(t('wires.messages.saveSuccess'))
    } catch (error) {
      const message = errMsg(error)
      ElMessage.error(
        t(message.startsWith('wires.') ? message : 'wires.messages.saveFailed', { msg: message }),
      )
    } finally {
      saving.value = false
    }
  }

  /** DELETE /graph：删除整图（调用方负责二次确认） */
  async function deleteAll(): Promise<void> {
    try {
      await deleteGraph()
      store.clear()
      store.markSaved()
      ElMessage.success(t('wires.messages.deleteSuccess'))
    } catch (error) {
      ElMessage.error(t('wires.messages.deleteFailed', { msg: errMsg(error) }))
    }
  }

  /** 导出快照 JSON 下载（对照上游 WiregraphDownloadModal） */
  function exportJson(): void {
    try {
      const json = JSON.stringify(store.toSnapshot(), null, 2)
      const blob = new Blob([json], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `wires-snapshot-${new Date().toISOString().replace(/[:.]/g, '-')}.json`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      ElMessage.error(t('wires.messages.exportFailed', { msg: errMsg(error) }))
    }
  }

  /**
   * 图 JSON（components/wires）里没有组件配置，factoryPid 无从得知：
   * 按 pid 向后端批量取配置补回 factoryPid 与属性（上游形态里配置本就与图 JSON 分开存放）。
   * 后端没有对应配置的 pid 保持为空，保存时给出明确提示。
   *
   * @param graph 导入的图 JSON
   * @returns 每个 pid 对应的属性表（含 service.factoryPid）
   */
  async function recoverConfigurations(
    graph: WireGraphSnapshot,
  ): Promise<Record<string, Record<string, unknown>>> {
    const pids = (graph.components ?? []).map((component) => component.pid)
    if (pids.length === 0) {
      return {}
    }
    return flattenComponentProperties(await getConfigsByPids(pids))
  }

  /**
   * 导入快照 JSON：既接受图 JSON（components/wires），也接受 {configs:[...]} 整份快照；
   * 图 JSON 缺配置时按 pid 向后端补齐，补不齐的组件提示出来（否则保存必然失败）；
   * 仅重建本地画布，须用户手动保存才落库。
   */
  async function importJson(file: File): Promise<void> {
    try {
      const parsed: unknown = JSON.parse(await file.text())
      const extracted = parseSnapshotFile(parsed)
      if (!extracted) {
        ElMessage.error(t('wires.messages.invalidSnapshot'))
        return
      }
      const configurationProperties =
        Object.keys(extracted.configurationProperties).length === 0 &&
        (extracted.graph.components ?? []).length > 0
          ? await recoverConfigurations(extracted.graph)
          : extracted.configurationProperties
      store.loadFromSnapshot(extracted.graph, configurationProperties)
      store.markDirty()
      const orphans = componentsWithoutFactoryPid()
      if (orphans.length > 0) {
        ElMessage.warning(t('wires.messages.importMissingConfig', { pids: orphans.join(', ') }))
        return
      }
      ElMessage.success(t('wires.messages.importSuccess'))
    } catch (error) {
      ElMessage.error(t('wires.messages.importFailed', { msg: errMsg(error) }))
    }
  }

  /** 从组件定义生成不冲突的唯一 PID（对照上游 onNewPidInserted） */
  function nextPid(descriptor: ComponentDescriptor): string {
    const segments = descriptor.factoryPid.split('.')
    const base =
      (segments[segments.length - 1] ?? 'component').replace(/[^A-Za-z0-9_-]/g, '') || 'component'
    let candidate = base
    let counter = 1
    while (store.componentByPid(candidate) !== null) {
      counter += 1
      candidate = `${base}${counter}`
    }
    return candidate
  }

  /**
   * 新建组件：端口数/默认属性取自 metadata（对照上游 fillRenderingProperties）。
   * `pid` 用于「把已存在的资产拖进图里」——此时必须复用它的 PID，不能另起名字。
   */
  function createComponent(descriptor: ComponentDescriptor, position: Point, pid?: string): void {
    store.addComponent({
      pid: pid ?? nextPid(descriptor),
      factoryPid: descriptor.factoryPid,
      inputPortCount: descriptor.defaultInputPorts,
      outputPortCount: descriptor.defaultOutputPorts,
      position,
      properties: metadata.defaultProperties(descriptor),
    })
  }

  return { loading, saving, load, save, deleteAll, exportJson, importJson, createComponent }
}
