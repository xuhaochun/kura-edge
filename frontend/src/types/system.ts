/**
 * 系统与热更新相关类型（对照 `kura-edge-rest` 的 SystemVersionDto / UpdateRequestDto / UpdateAcceptedDto）。
 *
 * 字段名与后端 DTO 严格对齐，是兼容契约，禁止改名（rules/05 §9）。
 */

/** 当前后端版本（GET /api/v1/system/version） */
export interface SystemVersion {
  /** 版本号；未知时为 unknown / dev */
  version: string
  /** 当前运行的 jar 绝对路径，未知时为空串 */
  jar: string
  /** 当前 jar 的 sha256，未知时为空串 */
  sha256: string
  /** 版本来源：desktop（外壳记录）或 maven（jar 清单） */
  source: string
  /** 图执行角色：leader 正在跑图，standby 已就绪未跑图（RFC 007 蓝绿） */
  role: 'leader' | 'standby'
  /** 是否具备蓝绿能力（PostgreSQL 咨询锁可用）；false 表示升级会有 15~25 s 中断 */
  blueGreen: boolean
}

/** 热更新请求（POST /api/v1/system/update）：只填 url 也受理，sha256/version 留空由后端补齐 */
export interface BackendUpdateRequest {
  /** jar 下载地址，只允许 http/https */
  url: string
  /** 期望的 sha256（64 位十六进制）；留空表示以实际下载到的摘要为准 */
  sha256?: string
  /** 目标版本号；留空表示从 URL 文件名推导（kura-edge-<版本>.jar） */
  version?: string
}

/** 热更新受理回执（202）：只代表已受理，落地结果看设备的 apply-result.json */
export interface BackendUpdateAccepted {
  version: string
  jar: string
  sha256: string
}

/** 自动更新（清单轮询）状态（GET /api/v1/system/feed，RFC 008） */
export interface FeedStatus {
  /** 是否配置了清单地址并会定时轮询 */
  enabled: boolean
  /** 清单地址，未配置时为空串 */
  url: string
  /** 轮询间隔（毫秒） */
  intervalMs: number
  /** 最近一次检查时间（ISO-8601），从未检查时为空串 */
  lastCheckAt: string
  /** 最近一次检查是否成功 */
  lastCheckOk: boolean
  /** 最近一次失败原因，成功时为空串 */
  lastError: string
  /** 清单里看到的版本 */
  latestSeenVersion: string
  /** 已提交给外壳的版本与时间 */
  lastStagedVersion: string
  lastStagedAt: string
  /** 最近失败的版本与时间（失败版本有冷却期，不会反复下载） */
  failedVersion: string
  failedAt: string
}
