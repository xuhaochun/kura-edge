/**
 * Drivers and Assets 页面的状态（对照上游 DriversAndAssetsListUi + AssetMgmtUi 的职责划分）。
 *
 * 一个 store 管三件事：
 * 1. 列表：驱动与资产实例（资产挂在驱动下展示，对照上游「Driver 行 + 缩进的 Asset 行」）；
 * 2. 选中项的配置：驱动表单（OCD 驱动）与资产通道表（通道描述符驱动，含脏标记）；
 * 3. 资产数据页：读值、改值、写回（状态与失败原因）。
 *
 * 网络与纯逻辑都在 api/ 与 composables/useAssetChannels 里，store 只编排状态。
 */
import { defineStore } from 'pinia'
import type { Ad, ConfigParameter } from '@/types/wires'
import type {
  AssetChannelDto,
  AssetDataRow,
  AssetInfo,
  ChannelColumn,
  ChannelRecordDto,
  ChannelRow,
  DriverInfo,
  WriteChannelDto,
} from '@/types/driversAssets'
import {
  createChannelRow,
  isChannelTableValid,
  parseChannelRows,
  serializeChannelRows,
  suggestChannelName,
  toCellString,
  toChannelColumns,
  validateChannelName,
} from '@/composables/useAssetChannels'
import type { ChannelNameError } from '@/composables/useAssetChannels'
import {
  createAsset,
  createDriver,
  deleteAsset,
  deleteDriver,
  getAssetChannelDescriptor,
  getAssetChannels,
  getAssetConfiguration,
  getAssetInfos,
  getDriverConfiguration,
  getDriverInfos,
  putAssetConfiguration,
  putDriverConfiguration,
  readChannels,
  writeChannels,
} from '@/api/driversAssets'
import { getMetadata } from '@/api/metadata'
import type { DriverDescriptorDto } from '@/types/driversAssets'

/** 选中项：驱动或资产 */
export interface SelectedEntry {
  kind: 'driver' | 'asset'
  pid: string
}

interface DriversAssetsState {
  loading: boolean
  saving: boolean
  drivers: DriverInfo[]
  assets: AssetInfo[]
  selected: SelectedEntry | null
  /** 驱动工厂 → 实例配置属性定义（GET /metadata 的 driverOCDs） */
  driverDefinitions: Record<string, Ad[]>
  /** 驱动实例 PID → 通道参数定义 */
  driverChannelDescriptors: Record<string, Ad[]>
  /** 资产基座通道参数定义 */
  baseChannelDescriptor: Ad[]
  /** 驱动实例 PID → 原始配置属性 */
  driverProperties: Record<string, unknown>
  /** 资产实例 PID → 原始配置属性 */
  assetProperties: Record<string, unknown>
  /** 资产 PID → 合并后的通道参数定义 */
  assetDescriptors: Record<string, Ad[]>
  /** 当前选中资产的通道行 */
  channelRows: ChannelRow[]
  /** 配置是否有未保存改动 */
  dirty: boolean
  /** 数据页：资产通道定义 */
  assetChannels: AssetChannelDto[]
  /** 数据页：行 */
  dataRows: AssetDataRow[]
  /** 数据页：最近一次整体错误（连接失败等） */
  dataError: string
  /** 数据页已读过至少一次 */
  dataLoaded: boolean
}

/** 从元数据里拆出驱动相关的两张表 */
function indexDriverMetadata(metadata: {
  driverOCDs?: { pid: string; definition?: { ads?: Ad[] | null } | null }[] | null
  driverChannelDescriptors?: DriverDescriptorDto[] | null
  assetChannelDescriptor?: Ad[] | null
}): {
  driverDefinitions: Record<string, Ad[]>
  driverChannelDescriptors: Record<string, Ad[]>
  baseChannelDescriptor: Ad[]
} {
  const driverDefinitions: Record<string, Ad[]> = {}
  for (const entry of metadata.driverOCDs ?? []) {
    driverDefinitions[entry.pid] = entry.definition?.ads ?? []
  }
  const driverChannelDescriptors: Record<string, Ad[]> = {}
  for (const entry of metadata.driverChannelDescriptors ?? []) {
    driverChannelDescriptors[entry.pid] = entry.channelDescriptor ?? []
  }
  return {
    driverDefinitions,
    driverChannelDescriptors,
    baseChannelDescriptor: metadata.assetChannelDescriptor ?? [],
  }
}

/** AD → 配置表单字段（值统一字符串承载，与线图配置区一致） */
export function toDriverParameter(ad: Ad): ConfigParameter {
  const options: Record<string, string> = {}
  for (const option of ad.options ?? []) {
    options[option.value] = option.label
  }
  return {
    id: ad.id,
    name: ad.name,
    description: ad.description ?? null,
    type: ad.type,
    required: ad.required ?? false,
    defaultValue:
      ad.defaultValue === undefined || ad.defaultValue === null ? null : String(ad.defaultValue),
    cardinality: 0,
    options: Object.keys(options).length > 0 ? options : null,
    min: ad.min === undefined || ad.min === null ? null : String(ad.min),
    max: ad.max === undefined || ad.max === null ? null : String(ad.max),
  }
}

/**
 * 属性表 → 表单值：只取表单字段声明过的键，值转字符串。
 *
 * @param properties 原始属性
 * @param parameters 表单字段
 * @returns 表单值
 */
export function toFormValues(
  properties: Record<string, unknown>,
  parameters: ConfigParameter[],
): Record<string, string> {
  const values: Record<string, string> = {}
  for (const parameter of parameters) {
    values[parameter.id] = toCellString(properties[parameter.id] ?? parameter.defaultValue)
  }
  return values
}

/**
 * 表单值 → 属性表：BOOLEAN 转真布尔、其余保持字符串，保留未在表单里的原始属性。
 *
 * @param properties 原始属性（保留其它键）
 * @param parameters 表单字段
 * @param values 表单值
 * @returns 新属性表
 */
export function toPropertyMap(
  properties: Record<string, unknown>,
  parameters: ConfigParameter[],
  values: Record<string, string>,
): Record<string, unknown> {
  const result: Record<string, unknown> = { ...properties }
  const booleanKeys = new Set(parameters.filter((p) => p.type === 'BOOLEAN').map((p) => p.id))
  for (const parameter of parameters) {
    const value = values[parameter.id]
    if (value === undefined) {
      continue
    }
    result[parameter.id] = booleanKeys.has(parameter.id) ? value === 'true' : value
  }
  return result
}

/** 记录 → 数据行状态推导（对照上游 getChannelStatus 的判定顺序） */
export function computeStatus(
  channel: AssetChannelDto,
  record: ChannelRecordDto | undefined,
  edited: boolean,
): AssetDataRow['status'] {
  if (!channel.enabled) {
    return 'DISABLED'
  }
  if (edited) {
    return 'MODIFIED'
  }
  if (!record) {
    return 'UNKNOWN'
  }
  return record.status === 'FAILURE' ? 'FAILURE' : 'SUCCESS'
}

export const useDriversAssetsStore = defineStore('driversAssets', {
  state: (): DriversAssetsState => ({
    loading: false,
    saving: false,
    drivers: [],
    assets: [],
    selected: null,
    driverDefinitions: {},
    driverChannelDescriptors: {},
    baseChannelDescriptor: [],
    driverProperties: {},
    assetProperties: {},
    assetDescriptors: {},
    channelRows: [],
    dirty: false,
    assetChannels: [],
    dataRows: [],
    dataError: '',
    dataLoaded: false,
  }),

  getters: {
    /** 资产按绑定的驱动分组（对照上游 HashMap<driverPid, List<assetPid>>） */
    assetsByDriver(state): Record<string, AssetInfo[]> {
      const grouped: Record<string, AssetInfo[]> = {}
      for (const asset of state.assets) {
        const key = asset.driverPid
        grouped[key] = grouped[key] ?? []
        grouped[key].push(asset)
      }
      return grouped
    },

    /** 当前选中的资产 PID（选中驱动时为空） */
    selectedAssetPid(state): string {
      return state.selected?.kind === 'asset' ? state.selected.pid : ''
    },

    /** 当前选中的驱动工厂 PID（选中资产时取它绑定的驱动工厂） */
    selectedDriverFactoryPid(state): string {
      if (state.selected?.kind === 'driver') {
        return state.drivers.find((driver) => driver.pid === state.selected?.pid)?.factoryPid ?? ''
      }
      if (state.selected?.kind === 'asset') {
        const asset = state.assets.find((candidate) => candidate.pid === state.selected?.pid)
        return state.drivers.find((driver) => driver.pid === asset?.driverPid)?.factoryPid ?? ''
      }
      return ''
    },

    /** 当前选中资产的通道列定义 */
    assetColumns(state): ChannelColumn[] {
      const pid = state.selected?.kind === 'asset' ? state.selected.pid : ''
      return toChannelColumns(state.assetDescriptors[pid] ?? [])
    },

    /** 驱动表单字段 */
    driverParameters(state): ConfigParameter[] {
      const pid = state.selected?.kind === 'driver' ? state.selected.pid : ''
      const factoryPid = state.drivers.find((driver) => driver.pid === pid)?.factoryPid ?? ''
      return (state.driverDefinitions[factoryPid] ?? []).map(toDriverParameter)
    },

    /** 数据页是否有待写入的改动 */
    hasModifiedValues(state): boolean {
      return state.dataRows.some((row) => row.status === 'MODIFIED')
    },

    /** 是否存在未保存的配置改动（切页签/切菜单前要拦） */
    hasUnsavedChanges(state): boolean {
      return state.dirty || state.dataRows.some((row) => row.status === 'MODIFIED')
    },
  },

  actions: {
    /** 加载列表与驱动元数据 */
    async load(): Promise<void> {
      this.loading = true
      try {
        const [drivers, assets, metadata] = await Promise.all([
          getDriverInfos(),
          getAssetInfos(),
          getMetadata(),
        ])
        this.drivers = drivers.map((entry) => ({ pid: entry.pid, factoryPid: entry.factoryPid }))
        this.assets = assets.map((entry) => ({
          pid: entry.pid,
          factoryPid: entry.factoryPid,
          driverPid: entry.driverPid ?? '',
          description: entry.description ?? '',
        }))
        const indexed = indexDriverMetadata(metadata)
        this.driverDefinitions = indexed.driverDefinitions
        this.driverChannelDescriptors = indexed.driverChannelDescriptors
        this.baseChannelDescriptor = indexed.baseChannelDescriptor
        if (this.selected && !this.exists(this.selected)) {
          this.selected = null
        }
      } finally {
        this.loading = false
      }
    },

    /** 选中项是否还存在（删除后清理选中） */
    exists(selected: SelectedEntry): boolean {
      return selected.kind === 'driver'
        ? this.drivers.some((driver) => driver.pid === selected.pid)
        : this.assets.some((asset) => asset.pid === selected.pid)
    },

    /** 选中一个实例并加载它的配置 */
    async select(kind: 'driver' | 'asset', pid: string): Promise<void> {
      this.selected = { kind, pid }
      this.dirty = false
      this.channelRows = []
      this.assetChannels = []
      this.dataRows = []
      this.dataError = ''
      this.dataLoaded = false
      if (kind === 'driver') {
        this.driverProperties = await getDriverConfiguration(pid)
      } else {
        await this.loadAssetConfiguration(pid)
      }
    },

    /** 重新拉取资产配置与通道描述符（Reset 也走这里） */
    async loadAssetConfiguration(pid: string): Promise<void> {
      const [properties, descriptor] = await Promise.all([
        getAssetConfiguration(pid),
        getAssetChannelDescriptor(pid),
      ])
      this.assetProperties = properties
      this.assetDescriptors = { ...this.assetDescriptors, [pid]: descriptor }
    },

    /**
     * 从属性表重建通道行（选中资产 / Reset 时调用）。
     * <p>
     * 缺省的通道参数用列默认值补齐——对齐上游「绑定驱动时把必填 AD 的默认值写进配置」的行为，
     * 否则界面会显示必填项为空、Apply 永远被拦。
     * </p>
     */
    reloadChannelRows(): void {
      const pid = this.selectedAssetPid
      if (!pid) {
        this.channelRows = []
        return
      }
      const columns = this.assetColumns
      const rows = parseChannelRows(this.assetProperties)
      for (const row of rows) {
        for (const column of columns) {
          if (row.params[column.id] === undefined) {
            row.params[column.id] = toCellString(column.defaultValue)
          }
        }
        if (row.params['+name'] === undefined || row.params['+name'] === '') {
          row.params['+name'] = row.name
        }
      }
      this.channelRows = rows
      this.dirty = false
    },

    /** 新建驱动实例 */
    async addDriver(factoryPid: string, pid: string): Promise<void> {
      await createDriver(factoryPid, pid)
      await this.load()
      await this.select('driver', pid)
    },

    /** 新建资产实例 */
    async addAsset(pid: string, driverPid: string, description: string): Promise<void> {
      await createAsset(pid, driverPid, description)
      await this.load()
      await this.select('asset', pid)
    },

    /** 删除选中实例（后端对被引用的情况返回 409003） */
    async removeSelected(): Promise<void> {
      const selected = this.selected
      if (!selected) {
        return
      }
      if (selected.kind === 'driver') {
        await deleteDriver(selected.pid)
      } else {
        await deleteAsset(selected.pid)
      }
      this.selected = null
      this.dirty = false
      this.channelRows = []
      await this.load()
    },

    /** 追加一个通道（按列默认值建行） */
    addChannel(): string {
      const name = suggestChannelName(this.channelRows)
      const columns = this.assetColumns
      if (columns.length === 0) {
        return ''
      }
      this.channelRows = [...this.channelRows, createChannelRow(name, columns)]
      this.dirty = true
      return name
    },

    /** 删除通道行（上游无二次确认） */
    removeChannel(index: number): void {
      this.channelRows = this.channelRows.filter((_, position) => position !== index)
      this.dirty = true
    },

    /** 改单元格（值统一字符串） */
    setCell(index: number, paramId: string, value: string): void {
      const row = this.channelRows[index]
      if (!row) {
        return
      }
      row.params[paramId] = value
      if (paramId === '+name') {
        row.name = value
      }
      this.dirty = true
    },

    /** 通道名校验（新建通道弹窗用） */
    validateName(name: string): ChannelNameError {
      return validateChannelName(name, this.channelRows)
    },

    /** 通道表整体是否可应用 */
    canApply(): boolean {
      return this.dirty && isChannelTableValid(this.channelRows, this.assetColumns)
    },

    /** 应用资产配置（后端按「删掉再建」语义重建实例） */
    async applyAssetConfiguration(): Promise<void> {
      const pid = this.selectedAssetPid
      if (!pid) {
        return
      }
      this.saving = true
      try {
        const properties = serializeChannelRows(
          this.assetProperties,
          this.channelRows,
          this.assetColumns,
        )
        await putAssetConfiguration(pid, properties)
        this.assetProperties = properties
        this.dirty = false
      } finally {
        this.saving = false
      }
    },

    /** 应用驱动配置 */
    async applyDriverConfiguration(values: Record<string, string>): Promise<void> {
      const pid = this.selected?.kind === 'driver' ? this.selected.pid : ''
      if (!pid) {
        return
      }
      this.saving = true
      try {
        const properties = toPropertyMap(this.driverProperties, this.driverParameters, values)
        await putDriverConfiguration(pid, properties)
        this.driverProperties = properties
        this.dirty = false
      } finally {
        this.saving = false
      }
    },

    /** 数据页：读全部通道（带状态），填充行 */
    async readAssetData(): Promise<void> {
      const pid = this.selectedAssetPid
      if (!pid) {
        return
      }
      this.dataError = ''
      try {
        const channels = await getAssetChannels(pid)
        const readable = channels.filter((channel) => channel.enabled && channel.type !== 'WRITE')
        const records =
          readable.length === 0
            ? []
            : await readChannels(
                pid,
                readable.map((c) => c.name),
              )
        const byName: Record<string, ChannelRecordDto> = {}
        for (const record of records) {
          byName[record.name] = record
        }
        this.assetChannels = channels
        this.dataRows = channels.map((channel) => {
          const record = byName[channel.name]
          const status = computeStatus(channel, record, false)
          return {
            name: channel.name,
            type: channel.type,
            valueType: channel.valueType,
            value: record?.value ?? null,
            edited: toCellString(record?.value ?? ''),
            status,
            exceptionMessage: record?.exceptionMessage ?? null,
          }
        })
        this.dataLoaded = true
      } catch (error) {
        this.dataError = error instanceof Error ? error.message : String(error)
        this.dataRows = []
      }
    },

    /** 数据页：改值 → 标记 MODIFIED */
    setDataValue(index: number, value: string): void {
      const row = this.dataRows[index]
      if (!row) {
        return
      }
      row.edited = value
      row.status = computeStatus(
        this.assetChannels.find((channel) => channel.name === row.name) ?? {
          name: row.name,
          type: row.type,
          valueType: row.valueType,
          scaleOffsetType: 'DEFINED_BY_VALUE_TYPE',
          enabled: true,
        },
        undefined,
        true,
      )
    },

    /** 数据页：把改过的值写回设备 */
    async writeAssetData(): Promise<void> {
      const pid = this.selectedAssetPid
      if (!pid) {
        return
      }
      const requests: WriteChannelDto[] = this.dataRows
        .filter((row) => row.status === 'MODIFIED')
        .map((row) => ({ name: row.name, type: row.valueType, value: row.edited }))
      if (requests.length === 0) {
        return
      }
      this.saving = true
      this.dataError = ''
      try {
        const records = await writeChannels(pid, requests)
        const byName: Record<string, ChannelRecordDto> = {}
        for (const record of records) {
          byName[record.name] = record
        }
        this.dataRows = this.dataRows.map((row) => {
          const record = byName[row.name]
          if (!record) {
            return row
          }
          return {
            ...row,
            value: record.value ?? row.edited,
            status: record.status === 'FAILURE' ? 'FAILURE' : 'SUCCESS',
            exceptionMessage: record.exceptionMessage ?? null,
          }
        })
      } catch (error) {
        this.dataError = error instanceof Error ? error.message : String(error)
      } finally {
        this.saving = false
      }
    },
  },
})
