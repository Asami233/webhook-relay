// 控制台与后端 /api/v1 的唯一通道。API Key 只放 sessionStorage：关闭标签页即失效，不跨标签页共享。

const KEY_STORAGE = 'relay.apiKey'

export type DeliveryStatus = 'PENDING' | 'IN_FLIGHT' | 'SUCCEEDED' | 'DEAD'

export interface EventSummary {
  id: number
  type: string
  idempotencyKey: string
  createdAt: string
}

export interface DeliveryView {
  id: number
  endpointId: number
  status: DeliveryStatus
  attempts: number
  nextAttemptAt: string
  lastStatusCode: number | null
  lastError: string | null
  updatedAt: string
}

export interface EventDetail extends EventSummary {
  payload: unknown
  deliveries: DeliveryView[]
}

export interface DeliverySummary {
  id: number
  eventId: number
  endpointId: number
  status: DeliveryStatus
  attempts: number
  replayCount: number
  nextAttemptAt: string
  lastStatusCode: number | null
  lastError: string | null
  createdAt: string
  updatedAt: string
}

export interface Attempt {
  attemptNo: number
  startedAt: string
  durationMs: number
  statusCode: number | null
  error: string | null
  responseSnippet: string | null
}

export interface DeliveryDetail {
  delivery: DeliverySummary
  attempts: Attempt[]
}

export interface Endpoint {
  id: number
  url: string
  description: string | null
  eventTypes: string[]
  enabled: boolean
  maxAttempts: number
  deliverQps: number
  createdAt: string
  updatedAt: string
}

export interface MinutePoint {
  minute: string
  ok: number
  failed: number
}

export interface Overview {
  events: number
  deliveries: Record<DeliveryStatus, number>
  attemptsPerMinute: MinutePoint[]
}

export interface SinkStats {
  received: number
  succeeded: number
  failed: number
  uniqueDelivered: number
  duplicates: number
  lastReceivedAt: string | null
}

export interface Sink {
  id: string
  name: string
  url: string
  config: { failureRate: number; failureStatus: number; latencyMs: number; jitterMs: number }
  stats: SinkStats
}

export interface DemoRun {
  runId: string
  eventType: string
  sinkId: string
  endpointId: number
  events: number
}

/** 后端错误统一为 RFC 9457 ProblemDetail；code 为机器可读类别。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message)
  }
}

export function getKey(): string | null {
  return sessionStorage.getItem(KEY_STORAGE)
}

export function setKey(key: string): void {
  sessionStorage.setItem(KEY_STORAGE, key)
}

export function clearKey(): void {
  sessionStorage.removeItem(KEY_STORAGE)
}

let onUnauthorized: () => void = () => {}

/** 由 main.ts 注册：Key 失效（被轮换、填错）时回到登录页。 */
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const key = getKey()
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(key ? { Authorization: `Bearer ${key}` } : {}),
      ...init.headers,
    },
  })
  if (res.status === 401) {
    clearKey()
    onUnauthorized()
    throw new ApiError(401, 'unauthorized', 'API Key 无效或已失效')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}) as Record<string, unknown>)
    const detail = typeof body.detail === 'string' ? body.detail : res.statusText
    throw new ApiError(res.status, typeof body.code === 'string' ? body.code : undefined, detail)
  }
  return (res.status === 204 ? undefined : await res.json()) as T
}

export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })

export const patch = <T>(path: string, body: unknown) =>
  api<T>(path, { method: 'PATCH', body: JSON.stringify(body) })

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
