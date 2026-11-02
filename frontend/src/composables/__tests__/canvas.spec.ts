/** 画布几何纯函数单测（对照上游 wires_composer.js clampToGrid） */
import { describe, expect, it } from 'vitest'
import {
  clampToGrid,
  parsePortHandle,
  parseWireEdgeId,
  portHandleId,
  wireEdgeId,
} from '@/composables/canvas'

describe('clampToGrid', () => {
  it('thenFloorsToGridMultiples', () => {
    expect(clampToGrid({ x: 45, y: -7 })).toEqual({ x: 40, y: -20 })
    expect(clampToGrid({ x: 40, y: 40 })).toEqual({ x: 40, y: 40 })
  })
})

describe('portHandleId / parsePortHandle', () => {
  it('thenRoundTrips', () => {
    expect(portHandleId('in', 2)).toBe('in2')
    expect(portHandleId('out', 0)).toBe('out0')
    expect(parsePortHandle('in2', 'in')).toBe(2)
    expect(parsePortHandle('out0', 'out')).toBe(0)
  })

  it('thenDefaultsToZeroOnMalformedHandle', () => {
    expect(parsePortHandle(null, 'in')).toBe(0)
    expect(parsePortHandle('junk', 'in')).toBe(0)
    expect(parsePortHandle('out1', 'in')).toBe(0)
  })
})

describe('wireEdgeId / parseWireEdgeId', () => {
  it('thenRoundTrips', () => {
    const wire = { emitterPid: 'a', emitterPort: 1, receiverPid: 'b', receiverPort: 0 }
    const id = wireEdgeId(wire)
    expect(id).toBe('wire:a:1:b:0')
    expect(parseWireEdgeId(id)).toEqual(wire)
  })

  it('thenReturnsNullOnMalformedId', () => {
    expect(parseWireEdgeId('not-an-edge')).toBeNull()
    expect(parseWireEdgeId('wire:a:x:b:0')).toBeNull()
  })
})
