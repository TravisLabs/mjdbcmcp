import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert, Badge, Card, CardBody, CardFooter, CardHeader, Col, Input, Pagination,
  PaginationItem, PaginationLink, Row, Table,
} from 'reactstrap'
import {
  api, duration, type QueryExecution, type RunningResponse, type StatsResponse, type Summary,
} from './api'

const WINDOWS = [
  { label: 'Last 15 minutes', value: 15 },
  { label: 'Last hour', value: 60 },
  { label: 'Last 24 hours', value: 1440 },
  { label: 'Last 7 days', value: 10080 },
]

const outcomeColour = (outcome: QueryExecution['outcome']) =>
  outcome === 'OK' ? 'success' : outcome === 'REFUSED' ? 'warning' : outcome === 'CANCELLED' ? 'secondary' : 'danger'

function getPaginationRange(current: number, total: number): (number | '...')[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  if (current <= 4) {
    return [1, 2, 3, 4, 5, '...', total]
  }
  if (current >= total - 3) {
    return [1, '...', total - 4, total - 3, total - 2, total - 1, total]
  }
  return [1, '...', current - 1, current, current + 1, '...', total]
}

/** Expandable SQL component for activity tables. Shows single-line preview with click-to-expand code block. */
function Sql({ sql }: { sql: string | null }) {
  const [expanded, setExpanded] = useState(false)
  if (!sql) return <span className="text-body-secondary">—</span>
  const flat = sql.replace(/\s+/g, ' ').trim()
  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        onClick={() => setExpanded(!expanded)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            setExpanded(!expanded)
          }
        }}
        className="d-inline-flex align-items-center gap-1 sql-expandable text-decoration-none"
        title={expanded ? 'Click to collapse' : 'Click to expand complete query'}
      >
        <span className="text-body-secondary" style={{ fontSize: '0.65rem' }}>
          {expanded ? '▼' : '▶'}
        </span>
        <code className="small text-break">
          {flat.length > 90 ? `${flat.slice(0, 90)}…` : flat}
        </code>
      </div>
      {expanded && (
        <pre className="sql-code-block mt-2 mb-0">
          <code>{sql}</code>
        </pre>
      )}
    </div>
  )
}

function StatTile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card className="h-100">
      <CardBody className="py-3">
        <div className="small text-body-secondary">{label}</div>
        <div className="fs-4">{value}</div>
        {hint && <div className="small text-body-secondary">{hint}</div>}
      </CardBody>
    </Card>
  )
}

function SummaryRow({ name, summary }: { name: string; summary: Summary }) {
  return (
    <tr>
      <td>{name}</td>
      <td className="text-end">{summary.calls}</td>
      <td className="text-end">
        {summary.refused > 0 ? <Badge color="warning">{summary.refused}</Badge> : '0'}
      </td>
      <td className="text-end">
        {summary.failed > 0 ? <Badge color="danger">{summary.failed}</Badge> : '0'}
      </td>
      <td className="text-end">
        {summary.cancelled > 0 ? <Badge color="secondary">{summary.cancelled}</Badge> : '0'}
      </td>
      <td className="text-end">{duration(summary.avgMs)}</td>
      <td className="text-end">{duration(summary.p95Ms)}</td>
      <td className="text-end">{duration(summary.maxMs)}</td>
      <td className="text-end">{summary.rowsReturned}</td>
    </tr>
  )
}

export default function ActivityPage() {
  const [live, setLive] = useState<RunningResponse | null>(null)
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [recent, setRecent] = useState<QueryExecution[]>([])
  const [windowMinutes, setWindowMinutes] = useState(60)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(25)
  const [error, setError] = useState<string | null>(null)
  // Ticks the clock so elapsed times count up between polls rather than freezing.
  const [, setTick] = useState(0)
  const windowRef = useRef(windowMinutes)
  windowRef.current = windowMinutes
  const pageRef = useRef(page)
  pageRef.current = page
  const pageSizeRef = useRef(pageSize)
  pageSizeRef.current = pageSize

  const pollRunning = useCallback(async () => {
    try {
      setLive(await api.running())
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  const pollStats = useCallback(async () => {
    try {
      const currentWindow = windowRef.current
      const currentPage = pageRef.current
      const currentPageSize = pageSizeRef.current
      const offset = Math.max(0, (currentPage - 1) * currentPageSize)

      const [s, r] = await Promise.all([
        api.stats(currentWindow),
        api.recent(currentWindow, currentPageSize, offset),
      ])
      setStats(s)
      setRecent(r)
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void pollRunning()
    void pollStats()
    // Running queries are the reason to look at this page, so they refresh fast; the aggregates
    // are a database read and do not need to.
    const fast = setInterval(() => void pollRunning(), 1000)
    const slow = setInterval(() => void pollStats(), 5000)
    const clock = setInterval(() => setTick((t) => t + 1), 250)
    return () => {
      clearInterval(fast)
      clearInterval(slow)
      clearInterval(clock)
    }
  }, [pollRunning, pollStats])

  useEffect(() => {
    void pollStats()
  }, [windowMinutes, page, pageSize, pollStats])

  const totalCalls = stats?.stats.overall.calls ?? 0
  const totalPages = Math.max(1, Math.ceil(totalCalls / pageSize))

  useEffect(() => {
    if (totalCalls > 0 && page > totalPages) {
      setPage(totalPages)
    }
  }, [totalCalls, page, totalPages])

  const onWindowChange = (mins: number) => {
    setWindowMinutes(mins)
    setPage(1)
  }

  const onPageSizeChange = (size: number) => {
    setPageSize(size)
    setPage(1)
  }

  const from = totalCalls === 0 ? 0 : (page - 1) * pageSize + 1
  const to = Math.min(totalCalls, page * pageSize)

  const overall = stats?.stats.overall
  const errorRate = overall && overall.calls > 0
    ? `${Math.round(((overall.refused + overall.failed) / overall.calls) * 100)}%`
    : '—'

  return (
    <>
      {error && <Alert color="danger">{error}</Alert>}
      {stats && !stats.enabled && (
        <Alert color="secondary">
          Query logging is switched off, so the statistics below stay empty. Running queries are
          tracked in memory and still shown.
        </Alert>
      )}
      {stats && stats.stats.droppedRecords > 0 && (
        <Alert color="warning">
          {stats.stats.droppedRecords} record(s) were dropped because the log writer fell behind, so
          the figures below undercount. Queries were never made to wait for the log.
        </Alert>
      )}

      <Card className="mb-4">
        <CardHeader className="d-flex justify-content-between align-items-center">
          <span>
            Running now
            {live && live.runningCount > 0 && <Badge color="info" className="ms-2">{live.runningCount}</Badge>}
          </span>
          <span className="small text-body-secondary">refreshing every second</span>
        </CardHeader>
        <CardBody className="p-0">
          <Table responsive hover className="mb-0 align-middle">
            <thead>
              <tr>
                <th>Elapsed</th>
                <th>Datasource</th>
                <th>Tool</th>
                <th>Statement</th>
                <th>Started</th>
              </tr>
            </thead>
            <tbody>
              {(!live || live.running.length === 0) && (
                <tr><td colSpan={5} className="text-center text-body-secondary py-4">
                  Nothing running.
                </td></tr>
              )}
              {live?.running.map((q) => {
                // Count up between polls, but never below the server's own reading — the browser's
                // clock may not agree with the server's, and a timer going backwards looks broken.
                const elapsed = Math.max(q.elapsedMs, Date.now() - new Date(q.startedAt).getTime())
                return (
                  <tr key={q.id}>
                    <td className="text-nowrap">
                      <Badge color={elapsed > 5000 ? 'danger' : elapsed > 1000 ? 'warning' : 'secondary'}>
                        {duration(elapsed)}
                      </Badge>
                    </td>
                    <td>
                      {q.datasource ?? <span className="text-body-secondary">—</span>}
                    </td>
                    <td><code>{q.tool}</code></td>
                    <td><Sql sql={q.sql} /></td>
                    <td className="small text-body-secondary text-nowrap">
                      {new Date(q.startedAt).toLocaleTimeString()}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </Table>
        </CardBody>
      </Card>

      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">Statistics</h5>
        <Input type="select" style={{ maxWidth: '14rem' }} value={windowMinutes}
               onChange={(e) => onWindowChange(Number(e.target.value))}>
          {WINDOWS.map((w) => <option key={w.value} value={w.value}>{w.label}</option>)}
        </Input>
      </div>

      <Row className="g-3 mb-4">
        <Col md={3}><StatTile label="Calls" value={String(overall?.calls ?? 0)}
                              hint={stats?.stats.windowFrom
                                ? `since ${new Date(stats.stats.windowFrom).toLocaleString()}`
                                : 'no records in this window'} /></Col>
        <Col md={3}><StatTile label="Refused, failed or cancelled" value={errorRate}
                              hint={`${overall?.refused ?? 0} refused, ${overall?.failed ?? 0} failed, ${overall?.cancelled ?? 0} cancelled`} /></Col>
        <Col md={3}><StatTile label="p95 duration" value={duration(overall?.p95Ms)}
                              hint={`median ${duration(overall?.p50Ms)}`} /></Col>
        <Col md={3}><StatTile label="Rows returned" value={String(overall?.rowsReturned ?? 0)}
                              hint={`slowest ${duration(overall?.maxMs)}`} /></Col>
      </Row>

      <Row>
        <Col lg={7}>
          <Card className="mb-4">
            <CardHeader>By datasource</CardHeader>
            <CardBody className="p-0">
              <Table responsive size="sm" className="mb-0">
                <thead>
                  <tr>
                    <th>Datasource</th><th className="text-end">Calls</th><th className="text-end">Refused</th>
                    <th className="text-end">Failed</th><th className="text-end">Cancelled</th><th className="text-end">Avg</th>
                    <th className="text-end">p95</th><th className="text-end">Max</th><th className="text-end">Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {(stats?.stats.byDatasource.length ?? 0) === 0 && (
                    <tr><td colSpan={9} className="text-center text-body-secondary py-3">No calls in this window.</td></tr>
                  )}
                  {stats?.stats.byDatasource.map((g) => <SummaryRow key={g.name} {...g} />)}
                </tbody>
              </Table>
            </CardBody>
          </Card>

          <Card className="mb-4">
            <CardHeader>By tool</CardHeader>
            <CardBody className="p-0">
              <Table responsive size="sm" className="mb-0">
                <thead>
                  <tr>
                    <th>Tool</th><th className="text-end">Calls</th><th className="text-end">Refused</th>
                    <th className="text-end">Failed</th><th className="text-end">Cancelled</th><th className="text-end">Avg</th>
                    <th className="text-end">p95</th><th className="text-end">Max</th><th className="text-end">Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {(stats?.stats.byTool.length ?? 0) === 0 && (
                    <tr><td colSpan={9} className="text-center text-body-secondary py-3">No calls in this window.</td></tr>
                  )}
                  {stats?.stats.byTool.map((g) => <SummaryRow key={g.name} {...g} />)}
                </tbody>
              </Table>
            </CardBody>
          </Card>
        </Col>

        <Col lg={5}>
          <Card className="mb-4">
            <CardHeader>Refusals</CardHeader>
            <CardBody>
              <p className="small text-body-secondary">
                A refusal is the server declining, not the database failing. A high count here means
                an agent is working against the capabilities or scope it was given.
              </p>
              {(stats?.stats.refusals.length ?? 0) === 0
                ? <div className="text-body-secondary small">None in this window.</div>
                : (
                  <Table size="sm" className="mb-0">
                    <tbody>
                      {stats?.stats.refusals.map((r) => (
                        <tr key={r.kind}>
                          <td><code>{r.kind}</code></td>
                          <td className="text-end">{r.count}</td>
                        </tr>
                      ))}
                    </tbody>
                  </Table>
                )}
            </CardBody>
          </Card>

          <Card className="mb-4">
            <CardHeader>Pools</CardHeader>
            <CardBody className="p-0">
              <Table responsive size="sm" className="mb-0">
                <thead>
                  <tr>
                    <th>Datasource</th><th className="text-end">Active</th><th className="text-end">Idle</th>
                    <th className="text-end">Waiting</th><th className="text-end">Max</th>
                  </tr>
                </thead>
                <tbody>
                  {(live?.pools.length ?? 0) === 0 && (
                    <tr><td colSpan={5} className="text-center text-body-secondary py-3">No pool has opened yet.</td></tr>
                  )}
                  {live?.pools.map((p) => (
                    <tr key={p.datasource}>
                      <td>
                        {p.datasource}
                      </td>
                      <td className="text-end">{p.active}</td>
                      <td className="text-end">{p.idle}</td>
                      <td className="text-end">
                        {p.awaiting > 0 ? <Badge color="warning">{p.awaiting}</Badge> : 0}
                      </td>
                      <td className="text-end">{p.max}</td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </CardBody>
          </Card>
        </Col>
      </Row>

      <Card className="mb-4">
        <CardHeader>Slowest in this window</CardHeader>
        <CardBody className="p-0">
          <Table responsive hover size="sm" className="mb-0 align-middle">
            <thead>
              <tr>
                <th>Duration</th><th>Datasource</th><th>Tool</th><th>Statement</th>
                <th>Rows</th><th>When</th>
              </tr>
            </thead>
            <tbody>
              {(stats?.stats.slowest.length ?? 0) === 0 && (
                <tr><td colSpan={6} className="text-center text-body-secondary py-3">No calls in this window.</td></tr>
              )}
              {stats?.stats.slowest.map((q) => (
                <tr key={q.id}>
                  <td className="text-nowrap">{duration(q.durationMs)}</td>
                  <td>{q.datasource ?? '—'}</td>
                  <td><code>{q.tool}</code></td>
                  <td><Sql sql={q.sql} /></td>
                  <td>{q.rowCount ?? '—'}{q.rowCapReached ? ' (capped)' : ''}</td>
                  <td className="small text-body-secondary text-nowrap">
                    {new Date(q.startedAt).toLocaleTimeString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </CardBody>
      </Card>

      <Card className="mb-4">
        <CardHeader className="d-flex justify-content-between align-items-center">
          <div className="d-flex align-items-center gap-2">
            <span>Recent calls</span>
            {totalCalls > 0 && <Badge color="secondary">{totalCalls}</Badge>}
          </div>
          <div className="d-flex align-items-center gap-2 small text-body-secondary">
            <span>Rows per page:</span>
            <Input
              type="select"
              bsSize="sm"
              style={{ width: 'auto' }}
              value={pageSize}
              onChange={(e) => onPageSizeChange(Number(e.target.value))}
            >
              <option value={10}>10</option>
              <option value={25}>25</option>
              <option value={50}>50</option>
              <option value={100}>100</option>
            </Input>
          </div>
        </CardHeader>
        <CardBody className="p-0">
          <Table responsive hover size="sm" className="mb-0 align-middle">
            <thead>
              <tr>
                <th>When</th><th>Outcome</th><th>Datasource</th><th>Tool</th>
                <th>Statement</th><th>Duration</th><th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {recent.length === 0 && (
                <tr><td colSpan={7} className="text-center text-body-secondary py-3">No calls in this window.</td></tr>
              )}
              {recent.map((q) => (
                <tr key={q.id}>
                  <td className="small text-body-secondary text-nowrap">
                    {new Date(q.startedAt).toLocaleTimeString()}
                  </td>
                  <td>
                    <Badge color={outcomeColour(q.outcome)}>
                      {q.refusalKind ?? q.outcome.toLowerCase()}
                    </Badge>
                  </td>
                  <td>{q.datasource ?? '—'}</td>
                  <td><code>{q.tool}</code></td>
                  <td><Sql sql={q.sql} /></td>
                  <td className="text-nowrap">{duration(q.durationMs)}</td>
                  <td className="small text-body-secondary">
                    {q.error
                      ? <span title={q.error}>{q.error.slice(0, 60)}{q.error.length > 60 ? '…' : ''}</span>
                      : q.rowCount !== null
                        ? `${q.rowCount} row(s)${q.rowCapReached ? ', capped' : ''}`
                        : q.updateCount !== null ? `${q.updateCount} updated` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </CardBody>
        {totalCalls > 0 && (
          <CardFooter className="d-flex flex-wrap justify-content-between align-items-center gap-2 py-2">
            <div className="small text-body-secondary">
              Showing {from}–{to} of {totalCalls} call{totalCalls === 1 ? '' : 's'}
            </div>
            {totalPages > 1 && (
              <Pagination size="sm" className="mb-0" aria-label="Recent calls pagination">
                <PaginationItem disabled={page <= 1}>
                  <PaginationLink first href="#" onClick={(e) => { e.preventDefault(); setPage(1) }} />
                </PaginationItem>
                <PaginationItem disabled={page <= 1}>
                  <PaginationLink previous href="#" onClick={(e) => { e.preventDefault(); setPage((p) => Math.max(1, p - 1)) }} />
                </PaginationItem>
                {getPaginationRange(page, totalPages).map((p, idx) =>
                  p === '...' ? (
                    <PaginationItem key={`ellipsis-${idx}`} disabled>
                      <PaginationLink href="#" onClick={(e) => e.preventDefault()}>…</PaginationLink>
                    </PaginationItem>
                  ) : (
                    <PaginationItem key={p} active={p === page}>
                      <PaginationLink href="#" onClick={(e) => { e.preventDefault(); setPage(Number(p)) }}>
                        {p}
                      </PaginationLink>
                    </PaginationItem>
                  )
                )}
                <PaginationItem disabled={page >= totalPages}>
                  <PaginationLink next href="#" onClick={(e) => { e.preventDefault(); setPage((p) => Math.min(totalPages, p + 1)) }} />
                </PaginationItem>
                <PaginationItem disabled={page >= totalPages}>
                  <PaginationLink last href="#" onClick={(e) => { e.preventDefault(); setPage(totalPages) }} />
                </PaginationItem>
              </Pagination>
            )}
          </CardFooter>
        )}
      </Card>
    </>
  )
}
