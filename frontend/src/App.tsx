import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Badge, Button, Card, CardBody, CardHeader, Col, Container, Nav, NavItem, NavLink,
  Navbar, NavbarBrand, Row, Table,
} from 'reactstrap'
import ActivityPage from './ActivityPage'
import DatasourceForm from './DatasourceForm'
import ThemeSwitcher from './components/ThemeSwitcher'
import { MangoLogo } from './components/icons'
import { api, emptyDatasource, type Datasource, type DriverInfo, type PoolStats, type ServerInfo } from './api'

/** Capabilities that write, so the badge can say so without listing all four. */
const isWrite = (capability: string) => capability !== 'select'

export default function App() {
  const [datasources, setDatasources] = useState<Datasource[]>([])
  const [pools, setPools] = useState<PoolStats[]>([])
  const [server, setServer] = useState<ServerInfo | null>(null)
  const [drivers, setDrivers] = useState<DriverInfo | null>(null)
  const [editing, setEditing] = useState<Datasource | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<'datasources' | 'activity'>('datasources')
  const [runningCount, setRunningCount] = useState(0)

  const refresh = useCallback(async () => {
    try {
      const [d, p, s, dr] = await Promise.all([api.datasources(), api.pools(), api.server(), api.drivers()])
      setDatasources(d)
      setPools(p)
      setServer(s)
      setDrivers(dr)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void refresh()
    // Pool counters and open transactions are only interesting while they move, so poll.
    const timer = setInterval(() => void api.pools().then(setPools).catch(() => undefined), 5000)
    // The running count sits on the tab so an operator sees work happening without switching to it.
    const activity = setInterval(
      () => void api.running().then((r) => setRunningCount(r.runningCount)).catch(() => undefined), 2000)
    return () => {
      clearInterval(timer)
      clearInterval(activity)
    }
  }, [refresh])

  const remove = async (d: Datasource) => {
    if (d.id === null || !confirm(`Delete datasource "${d.name}"?`)) return
    try {
      await api.remove(d.id)
      await refresh()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const poolFor = (name: string) => pools.find((p) => p.datasource === name)

  return (
    <>
      <Navbar className="mango-navbar mb-4">
        <Container className="d-flex align-items-center gap-3">
          <NavbarBrand href="/" className="d-flex align-items-center gap-2 text-decoration-none mb-0">
            <MangoLogo size="1.8em" />
            <span className="mpa-brand-label">Mango JDBC MCP</span>
          </NavbarBrand>
          {server && (
            <span className="mpa-navbar-meta ms-auto d-none d-md-inline">
              MCP endpoint <code>{server.endpoint}</code> · {server.tools.length} tools
            </span>
          )}
          <div className={server ? '' : 'ms-auto'}>
            <ThemeSwitcher />
          </div>
        </Container>
      </Navbar>

      <Container className="pb-5">
        {error && <Alert color="danger">{error}</Alert>}
        {server?.warnings.map((warning) => (
          <Alert color="warning" key={warning}>{warning}</Alert>
        ))}

        <Nav tabs className="mb-4">
          <NavItem>
            <NavLink href="#" active={tab === 'datasources'}
                     onClick={(e) => { e.preventDefault(); setTab('datasources') }}>
              Datasources
            </NavLink>
          </NavItem>
          <NavItem>
            <NavLink href="#" active={tab === 'activity'}
                     onClick={(e) => { e.preventDefault(); setTab('activity') }}>
              Activity
              {runningCount > 0 && <Badge color="info" className="ms-2">{runningCount}</Badge>}
            </NavLink>
          </NavItem>
        </Nav>

        {tab === 'activity' && <ActivityPage />}

        {tab === 'datasources' && <>
        <Card className="mb-4">
          <CardHeader className="d-flex justify-content-between align-items-center">
            <span>Datasources</span>
            <Button color="primary" size="sm" onClick={() => setEditing(emptyDatasource())}>Add datasource</Button>
          </CardHeader>
          <CardBody className="p-0">
            <Table responsive hover className="mb-0 align-middle">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>JDBC URL</th>
                  <th>Capabilities</th>
                  <th>Scope</th>
                  <th>Pool</th>
                  <th className="text-end">Actions</th>
                </tr>
              </thead>
              <tbody>
                {datasources.length === 0 && (
                  <tr><td colSpan={6} className="text-center text-body-secondary py-4">
                    No datasources configured yet.
                  </td></tr>
                )}
                {datasources.map((d) => {
                  const pool = poolFor(d.name)
                  return (
                    <tr key={d.id}>
                      <td>
                        <div>{d.name}</div>
                        {d.description && <small className="text-body-secondary">{d.description}</small>}
                        {!d.enabled && <div><Badge color="secondary">disabled</Badge></div>}
                      </td>
                      <td className="text-break"><code>{d.jdbcUrl}</code></td>
                      <td>
                        {d.capabilities.length === 0
                          ? <Badge color="danger">none</Badge>
                          : d.capabilities.map((c) => (
                              <Badge key={c} color={isWrite(c) ? 'warning' : 'secondary'} className="me-1">{c}</Badge>
                            ))}
                      </td>
                      <td className="small">
                        {d.objectAllowlist
                          ? d.objectAllowlist.split('\n').map((line) => <div key={line}><code>{line}</code></div>)
                          : <span className="text-body-secondary">unrestricted</span>}
                      </td>
                      <td className="small">
                        {pool
                          ? <>{pool.active} active / {pool.idle} idle / {pool.max} max</>
                          : <span className="text-body-secondary">not opened</span>}
                      </td>
                      <td className="text-end text-nowrap">
                        <Button size="sm" color="secondary" outline className="me-2"
                                onClick={() => setEditing({ ...d, password: '' })}>
                          Edit
                        </Button>
                        <Button size="sm" color="danger" outline onClick={() => void remove(d)}>Delete</Button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </Table>
          </CardBody>
        </Card>

        <Row>
          <Col md={6}>
            <Card className="mb-4">
              <CardHeader>Tool surface</CardHeader>
              <CardBody>
                <p className="small text-body-secondary">
                  Derived from the capabilities granted across enabled datasources — a tool an agent
                  cannot use anywhere is not offered at all.
                </p>
                {server?.tools.map((t) => (
                  <div key={t.name} className="mb-2">
                    <code>{t.name}</code>
                    <div className="small text-body-secondary">{t.description}</div>
                  </div>
                ))}
              </CardBody>
            </Card>
          </Col>
          <Col md={6}>
            <Card className="mb-4">
              <CardHeader>Drivers</CardHeader>
              <CardBody>
                <p className="small text-body-secondary mb-2">
                  Drop extra driver JARs into <code>{drivers?.directory}</code> and restart. Those are
                  matched by URL, so leave the driver class blank for them.
                </p>
                <ul className="small mb-0">
                  {drivers?.registered.map((d) => <li key={d}><code>{d}</code></li>)}
                </ul>
              </CardBody>
            </Card>
          </Col>
        </Row>
        </>}
      </Container>

      {editing && (
        <DatasourceForm
          datasource={editing}
          server={server}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            void refresh()
          }}
        />
      )}
    </>
  )
}
