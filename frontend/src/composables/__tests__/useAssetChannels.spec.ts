/**
 * 通道表纯逻辑单测（对照上游 AssetModelImpl + LegacyChannelModel 的规则）：
 * 列派生、属性表 ⇄ 通道行、通道名校验、默认行与整体合法性。
 */
import { describe, expect, it } from 'vitest'
import type { Ad } from '@/types/wires'
import {
  CHANNEL_SEPARATOR,
  createChannelRow,
  isBaseParameter,
  isCellMissing,
  isChannelTableValid,
  parameterLabel,
  parseChannelRows,
  serializeChannelRows,
  suggestChannelName,
  toCellString,
  toChannelColumns,
  validateChannelName,
} from '@/composables/useAssetChannels'

const ad = (overrides: Partial<Ad>): Ad => ({
  id: '+type',
  name: 'type',
  type: 'STRING',
  required: true,
  ...overrides,
})

const descriptor: Ad[] = [
  ad({ id: '+enabled', name: 'enabled', type: 'BOOLEAN', defaultValue: true }),
  ad({ id: '+name', name: 'name', defaultValue: 'Channel-1' }),
  ad({
    id: '+type',
    name: 'type',
    defaultValue: 'READ',
    options: [
      { label: 'READ', value: 'READ' },
      { label: 'READ_WRITE', value: 'READ_WRITE' },
    ],
  }),
  ad({ id: '+value.type', name: 'value.type', defaultValue: 'INTEGER' }),
  ad({
    id: 'simulated.register',
    name: 'simulated.register',
    required: true,
    defaultValue: 'reg-1',
  }),
]

describe('useAssetChannels', () => {
  it('thenDetectsBaseParametersByPlusPrefix', () => {
    expect(isBaseParameter(ad({ id: '+enabled' }))).toBe(true)
    expect(isBaseParameter(ad({ id: 'simulated.register' }))).toBe(false)
    expect(parameterLabel('+value.type')).toBe('value.type')
    expect(parameterLabel('simulated.register')).toBe('simulated.register')
  })

  it('thenKeepsDescriptorOrderAndBuildsOptionMaps', () => {
    const columns = toChannelColumns(descriptor)

    expect(columns.map((column) => column.id)).toEqual([
      '+enabled',
      '+name',
      '+type',
      '+value.type',
      'simulated.register',
    ])
    expect(columns[2].options).toEqual({ READ: 'READ', READ_WRITE: 'READ_WRITE' })
    expect(columns[0].base).toBe(true)
    expect(columns[4].base).toBe(false)
    expect(toChannelColumns(null)).toEqual([])
  })

  it('thenRendersCellStringsForAnyValueType', () => {
    expect(toCellString(true)).toBe('true')
    expect(toCellString(10)).toBe('10')
    expect(toCellString(null)).toBe('')
    expect(toCellString(undefined)).toBe('')
  })

  it('thenParsesFlatPropertiesIntoRowsInOrder', () => {
    const rows = parseChannelRows({
      'driver.pid': 'driver-1',
      'ch-b#+type': 'READ',
      'ch-a#+type': 'WRITE',
      'ch-a#simulated.register': 'reg-9',
      bad: 'ignored',
      'trailing#': 'ignored',
    })

    expect(rows.map((row) => row.name)).toEqual(['ch-b', 'ch-a'])
    expect(rows[1].params['simulated.register']).toBe('reg-9')
    expect(parseChannelRows(null)).toEqual([])
  })

  it('thenSerializesRowsBackKeepingAssetLevelProperties', () => {
    const properties = { 'driver.pid': 'driver-1', 'asset.desc': 'demo' }
    const rows = [
      { name: 'ch-a', params: { '+enabled': 'true', '+type': 'READ', '+value.type': 'INTEGER' } },
    ]

    const serialized = serializeChannelRows(properties, rows, toChannelColumns(descriptor))

    expect(serialized['driver.pid']).toBe('driver-1')
    expect(serialized['asset.desc']).toBe('demo')
    expect(serialized[`ch-a${CHANNEL_SEPARATOR}+type`]).toBe('READ')
    expect(serialized[`ch-a${CHANNEL_SEPARATOR}+enabled`]).toBe(true)
  })

  it('thenBuildsNewChannelRowFromColumnDefaults', () => {
    const row = createChannelRow('Channel-2', toChannelColumns(descriptor))

    expect(row.params['+type']).toBe('READ')
    expect(row.params['+value.type']).toBe('INTEGER')
    expect(row.params['+enabled']).toBe('true')
    expect(row.params['simulated.register']).toBe('reg-1')
    expect(row.params['+name']).toBe('Channel-2')
  })

  it('thenSuggestsFirstFreeChannelName', () => {
    expect(suggestChannelName([])).toBe('Channel-1')
    expect(suggestChannelName([{ name: 'Channel-1', params: {} }])).toBe('Channel-2')
    expect(
      suggestChannelName([
        { name: 'Channel-1', params: {} },
        { name: 'Channel-2', params: {} },
      ]),
    ).toBe('Channel-3')
  })

  it('thenValidatesChannelNamesUsingUpstreamRules', () => {
    const rows = [{ name: 'taken', params: {} }]

    expect(validateChannelName('', rows)).toBe('empty')
    expect(validateChannelName('   ', rows)).toBe('empty')
    expect(validateChannelName('bad name', rows)).toBe('invalidCharacters')
    expect(validateChannelName('bad#name', rows)).toBe('invalidCharacters')
    expect(validateChannelName('bad/name', rows)).toBe('invalidCharacters')
    expect(validateChannelName('bad+name', rows)).toBe('invalidCharacters')
    expect(validateChannelName('bad,name', rows)).toBe('invalidCharacters')
    expect(validateChannelName('taken', rows)).toBe('duplicate')
    expect(validateChannelName('ok_name', rows)).toBe('')
  })

  it('thenTreatsMissingRequiredCellAsInvalid', () => {
    const columns = toChannelColumns(descriptor)
    const row = {
      name: 'ch',
      params: { '+type': 'READ', '+value.type': '', 'simulated.register': 'r' },
    }

    expect(isCellMissing(row, columns[2])).toBe(false)
    expect(isCellMissing(row, columns[3])).toBe(true)
    expect(isChannelTableValid([row], columns)).toBe(false)
  })

  it('thenRejectsUnknownEnumTokensInRows', () => {
    const columns = toChannelColumns(descriptor)
    const row = {
      name: 'ch',
      params: { '+type': 'SOMETHING', '+value.type': 'INTEGER', 'simulated.register': 'r' },
    }

    expect(isChannelTableValid([row], columns)).toBe(false)
    expect(isChannelTableValid([], columns)).toBe(true)
  })
})
