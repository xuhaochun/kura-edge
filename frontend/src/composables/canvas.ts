/** 画布几何与 ID 映射的纯函数（对照上游 wires_composer.js 的 clampToGrid / 端口命名） */

/** 网格尺寸，对照上游 paper.gridSize = 20 */
export const GRID_SIZE = 20

export interface Point {
  x: number
  y: number
}

/** 网格吸附（上游 clampToGrid：Math.floor(pos/gridSize)*gridSize） */
export function clampToGrid(pos: Point): Point {
  return {
    x: Math.floor(pos.x / GRID_SIZE) * GRID_SIZE,
    y: Math.floor(pos.y / GRID_SIZE) * GRID_SIZE,
  }
}

/** 端口 handle 的 DOM id：in0/in1… out0/out1… */
export function portHandleId(direction: 'in' | 'out', port: number): string {
  return `${direction}${port}`
}

/** 从 handle id 解析端口编号 */
export function parsePortHandle(
  handle: string | null | undefined,
  direction: 'in' | 'out',
): number {
  if (!handle || !handle.startsWith(direction)) {
    return 0
  }
  const parsed = Number.parseInt(handle.slice(direction.length), 10)
  return Number.isNaN(parsed) ? 0 : parsed
}

/** Vue Flow 边 id 与 store 连线的映射（保持与 store 双向可查） */
export function wireEdgeId(wire: {
  emitterPid: string
  emitterPort: number
  receiverPid: string
  receiverPort: number
}): string {
  return `wire:${wire.emitterPid}:${wire.emitterPort}:${wire.receiverPid}:${wire.receiverPort}`
}

/** 从边 id 解析回连线 */
export function parseWireEdgeId(
  id: string,
): { emitterPid: string; emitterPort: number; receiverPid: string; receiverPort: number } | null {
  const parts = id.split(':')
  if (parts.length !== 5 || parts[0] !== 'wire') {
    return null
  }
  const emitterPort = Number.parseInt(parts[2], 10)
  const receiverPort = Number.parseInt(parts[4], 10)
  if (Number.isNaN(emitterPort) || Number.isNaN(receiverPort)) {
    return null
  }
  return {
    emitterPid: parts[1],
    emitterPort,
    receiverPid: parts[3],
    receiverPort,
  }
}
