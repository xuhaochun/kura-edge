/** useGraphValidation 纯函数单测（Gherkin 风格沿用上游 AGENTS.md） */
import { describe, expect, it } from 'vitest'
import {
  hasCycle,
  isDuplicateWire,
  isWirePortValid,
  toPropertyScalar,
  validateParameter,
  wouldCreateCycle,
} from '@/composables/useGraphValidation'
import type { ConfigParameter } from '@/types/wires'

const param = (overrides: Partial<ConfigParameter>): ConfigParameter => ({
  id: 'p1',
  name: 'P1',
  type: 'STRING',
  required: false,
  cardinality: 0,
  ...overrides,
})

describe('hasCycle', () => {
  it('thenReturnsFalseWhenGraphIsAcyclic', () => {
    expect(hasCycle([{ emitterPid: 'a', receiverPid: 'b' }])).toBe(false)
    expect(
      hasCycle([
        { emitterPid: 'a', receiverPid: 'b' },
        { emitterPid: 'a', receiverPid: 'c' },
        { emitterPid: 'b', receiverPid: 'd' },
        { emitterPid: 'c', receiverPid: 'd' },
      ]),
    ).toBe(false)
  })

  it('thenReturnsTrueWhenGraphContainsCycle', () => {
    expect(
      hasCycle([
        { emitterPid: 'a', receiverPid: 'b' },
        { emitterPid: 'b', receiverPid: 'c' },
        { emitterPid: 'c', receiverPid: 'a' },
      ]),
    ).toBe(true)
  })
})

describe('wouldCreateCycle', () => {
  it('thenRejectsSelfLoop', () => {
    expect(wouldCreateCycle([], { emitterPid: 'a', receiverPid: 'a' })).toBe(true)
  })

  it('thenRejectsBackEdgeClosingCycle', () => {
    const existing = [{ emitterPid: 'a', receiverPid: 'b' }]
    expect(wouldCreateCycle(existing, { emitterPid: 'b', receiverPid: 'a' })).toBe(true)
  })

  it('thenAcceptsPlainEdge', () => {
    const existing = [{ emitterPid: 'a', receiverPid: 'b' }]
    expect(wouldCreateCycle(existing, { emitterPid: 'a', receiverPid: 'c' })).toBe(false)
  })
})

describe('isDuplicateWire', () => {
  const wires = [{ emitterPid: 'a', emitterPort: 0, receiverPid: 'b', receiverPort: 0 }]

  it('thenDetectsIdenticalWire', () => {
    expect(
      isDuplicateWire(wires, {
        emitterPid: 'a',
        emitterPort: 0,
        receiverPid: 'b',
        receiverPort: 0,
      }),
    ).toBe(true)
  })

  it('thenAcceptsDifferentPortCombination', () => {
    expect(
      isDuplicateWire(wires, {
        emitterPid: 'a',
        emitterPort: 1,
        receiverPid: 'b',
        receiverPort: 0,
      }),
    ).toBe(false)
  })
})

describe('isWirePortValid', () => {
  it('thenAcceptsPortsInRange', () => {
    expect(isWirePortValid({ emitterPort: 0, receiverPort: 1 }, 2, 3)).toBe(true)
  })

  it('thenRejectsOutOfRangePorts', () => {
    expect(isWirePortValid({ emitterPort: 2, receiverPort: 0 }, 2, 3)).toBe(false)
    expect(isWirePortValid({ emitterPort: 0, receiverPort: -1 }, 2, 3)).toBe(false)
  })
})

describe('validateParameter', () => {
  it('thenFailsWhenRequiredIsEmpty', () => {
    expect(validateParameter(param({ required: true }), '')).toEqual({
      valid: false,
      messageKey: 'wires.validation.required',
    })
  })

  it('thenPassesWhenOptionalIsEmpty', () => {
    expect(validateParameter(param({ required: false }), '')).toEqual({ valid: true })
  })

  it('thenFailsOnNonNumericValueForNumberType', () => {
    expect(validateParameter(param({ type: 'INTEGER' }), 'abc')).toEqual({
      valid: false,
      messageKey: 'wires.validation.number',
    })
  })

  it('thenFailsOnFractionalInteger', () => {
    expect(validateParameter(param({ type: 'LONG' }), '1.5')).toEqual({
      valid: false,
      messageKey: 'wires.validation.integer',
    })
  })

  it('thenFailsBelowMinAndAboveMax', () => {
    const bounded = param({ type: 'INTEGER', min: '0', max: '10' })
    expect(validateParameter(bounded, '-1')).toEqual({
      valid: false,
      messageKey: 'wires.validation.min',
      params: { min: '0' },
    })
    expect(validateParameter(bounded, '11')).toEqual({
      valid: false,
      messageKey: 'wires.validation.max',
      params: { max: '10' },
    })
    expect(validateParameter(bounded, '5').valid).toBe(true)
  })

  it('thenRestrictsValueToOptions', () => {
    const optioned = param({ options: { A: '1' } })
    expect(validateParameter(optioned, 'A').valid).toBe(true)
    expect(validateParameter(optioned, 'X')).toEqual({
      valid: false,
      messageKey: 'wires.validation.options',
    })
  })

  it('thenRequiresTrueOrFalseForBoolean', () => {
    expect(validateParameter(param({ type: 'BOOLEAN' }), 'true').valid).toBe(true)
    expect(validateParameter(param({ type: 'BOOLEAN' }), 'yes')).toEqual({
      valid: false,
      messageKey: 'wires.validation.boolean',
    })
  })

  it('thenRequiresSingleCharForCharType', () => {
    expect(validateParameter(param({ type: 'CHAR' }), 'a').valid).toBe(true)
    expect(validateParameter(param({ type: 'CHAR' }), 'ab')).toEqual({
      valid: false,
      messageKey: 'wires.validation.char',
    })
  })
})

describe('toPropertyScalar', () => {
  it('thenConvertsNumericTypes', () => {
    expect(toPropertyScalar(param({ type: 'INTEGER' }), ' 42 ')).toBe(42)
    expect(toPropertyScalar(param({ type: 'DOUBLE' }), '1.5')).toBe(1.5)
  })

  it('thenConvertsBoolean', () => {
    expect(toPropertyScalar(param({ type: 'BOOLEAN' }), 'true')).toBe(true)
    expect(toPropertyScalar(param({ type: 'BOOLEAN' }), 'false')).toBe(false)
  })

  it('thenKeepsStringsVerbatim', () => {
    expect(toPropertyScalar(param({ type: 'STRING' }), 'abc')).toBe('abc')
    expect(toPropertyScalar(param({ type: 'PASSWORD' }), null)).toBe('')
  })
})
