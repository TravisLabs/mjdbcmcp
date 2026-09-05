/** Wire form of a Datasource. Mirrors DatasourceDto; the password only ever travels outbound. */
export interface Datasource {
  id: number | null
  name: string
  description: string | null
  jdbcUrl: string
  driverClass: string | null
  username: string | null
  password?: string | null
  hasPassword?: boolean
  capabilities: string[]
  objectAllowlist: string | null
  disabledTools: string[]
  enabled: boolean
  defaultSchema: string | null
  maxRows: number
  maxCellChars: number
  queryTimeoutSeconds: number
  maxPoolSize: number
  minIdle: number
  connectionTimeoutMs: number
  idleTimeoutMs: number
  maxLifetimeMs: number
  validationQuery: string | null
}

export interface PoolStats {
  datasource: string
  total: number
  active: number
  idle: number
  awaiting: number
  max: number
}

export interface ServerInfo {
  name: string
  endpoint: string
  configDir: string
  capabilities: string[]
  tools: { name: string; description: string }[]
  warnings: string[]
}

export interface DriverInfo {
  registered: string[]
  external: string[]
  directory: string
}

export interface RunningQuery {
  id: number
  datasource: string | null
  tool: string
  sql: string | null
  startedAt: string
  elapsedMs: number
}

export interface QueryExecution {
  id: number
  datasource: string | null
  tool: string
  sql: string | null
  startedAt: string
  durationMs: number
  outcome: 'OK' | 'REFUSED' | 'FAILED' | 'CANCELLED'
  refusalKind: string | null
  error: string | null
  rowCount: number | null
  rowCapReached: boolean | null
  updateCount: number | null
}

export interface Summary {
  calls: number
  ok: number
  refused: number
  failed: number
  cancelled: number
  avgMs: number | null
  p50Ms: number | null
  p95Ms: number | null
  maxMs: number | null
  rowsReturned: number
}

export interface QueryStats {
  overall: Summary
  byDatasource: { name: string; summary: Summary }[]
  byTool: { name: string; summary: Summary }[]
  refusals: { kind: string; count: number }[]
  slowest: QueryExecution[]
  windowFrom: string | null
  droppedRecords: number
}

export interface RunningResponse {
  running: RunningQuery[]
  runningCount: number
  pools: PoolStats[]
}

export interface StatsResponse {
  enabled: boolean
  windowMinutes: number
  stats: QueryStats
}

/** What each Capability lets a Datasource run, in the operator's terms. */
export const CAPABILITY_HELP: Record<string, string> = {
  select: 'Read rows. On its own this also makes every connection read-only at the database.',
  dml: 'INSERT, UPDATE, DELETE, MERGE.',
  ddl_create: 'CREATE tables, views, indexes.',
  ddl_alter: 'ALTER existing objects.',
  ddl_drop: 'DROP objects, and TRUNCATE — grouped together because both discard data outright.',
}

export const ALL_TOOLS: { name: string; label: string }[] = [
  { name: 'database_info', label: 'database_info — Product, version and driver details' },
  { name: 'list_schemas', label: 'list_schemas — List schemas (allowlist filtered)' },
  { name: 'list_tables', label: 'list_tables — List tables and views (allowlist filtered)' },
  { name: 'describe_table', label: 'describe_table — Columns, keys, and indexes for a table' },
  { name: 'query', label: 'query — Structured typed read builder' },
  { name: 'raw_query', label: 'raw_query — Single read-only SELECT statement' },
  { name: 'explain_query', label: 'explain_query — Query execution plan generation' },
  { name: 'raw_execute', label: 'raw_execute — Single statement or atomic write/DDL script' },
]

export const emptyDatasource = (): Datasource => ({
  id: null,
  name: '',
  description: '',
  jdbcUrl: '',
  driverClass: '',
  username: '',
  password: '',
  capabilities: ['select'],
  objectAllowlist: '',
  disabledTools: [],
  enabled: true,
  defaultSchema: '',
  maxRows: 1000,
  maxCellChars: 4096,
  queryTimeoutSeconds: 30,
  maxPoolSize: 5,
  minIdle: 0,
  connectionTimeoutMs: 30000,
  idleTimeoutMs: 600000,
  maxLifetimeMs: 1800000,
  validationQuery: '',
})

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error ?? `${response.status} ${response.statusText}`)
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

export const api = {
  datasources: () => request<Datasource[]>('/datasources'),
  create: (d: Datasource) => request<Datasource>('/datasources', { method: 'POST', body: JSON.stringify(d) }),
  update: (id: number, d: Datasource) =>
    request<Datasource>(`/datasources/${id}`, { method: 'PUT', body: JSON.stringify(d) }),
  remove: (id: number) => request<void>(`/datasources/${id}`, { method: 'DELETE' }),
  test: (d: Datasource) =>
    request<{ ok: boolean; error?: string }>('/datasources/test', { method: 'POST', body: JSON.stringify(d) }),
  pools: () => request<PoolStats[]>('/pools'),
  server: () => request<ServerInfo>('/server'),
  drivers: () => request<DriverInfo>('/drivers'),
  running: () => request<RunningResponse>('/activity/running'),
  stats: (windowMinutes: number) => request<StatsResponse>(`/activity/stats?windowMinutes=${windowMinutes}`),
  recent: (windowMinutes: number, limit = 50, offset = 0) =>
    request<QueryExecution[]>(`/activity/recent?windowMinutes=${windowMinutes}&limit=${limit}&offset=${offset}`),
}

/** ms → a short human duration. Long queries are the point, so seconds beat four-digit milliseconds. */
export const duration = (ms: number | null | undefined): string => {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`
  const minutes = Math.floor(ms / 60_000)
  return `${minutes}m ${Math.round((ms % 60_000) / 1000)}s`
}
