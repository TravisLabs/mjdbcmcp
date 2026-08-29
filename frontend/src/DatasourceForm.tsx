import { useState } from 'react'
import {
  Alert, Button, Col, Form, FormGroup, FormText, Input, Label,
  Modal, ModalBody, ModalFooter, ModalHeader, Row, Spinner,
} from 'reactstrap'
import { ALL_TOOLS, api, CAPABILITY_HELP, type Datasource, type ServerInfo } from './api'

interface Props {
  datasource: Datasource
  server: ServerInfo | null
  onClose: () => void
  onSaved: () => void
}

/**
 * Create/edit dialog. Capabilities come first because they decide what the agent can do at all —
 * and, across all datasources, which tools exist on the MCP endpoint.
 */
export default function DatasourceForm({ datasource, server, onClose, onSaved }: Props) {
  const [draft, setDraft] = useState<Datasource>(datasource)
  const [error, setError] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const set = <K extends keyof Datasource>(key: K, value: Datasource[K]) =>
    setDraft((previous) => ({ ...previous, [key]: value }))

  const num = (key: keyof Datasource) => (e: React.ChangeEvent<HTMLInputElement>) =>
    set(key, Number(e.target.value) as never)

  const toggleCapability = (capability: string, on: boolean) =>
    set('capabilities', on
      ? [...draft.capabilities, capability]
      : draft.capabilities.filter((c) => c !== capability))

  const toggleToolEnabled = (toolName: string, enabled: boolean) => {
    const disabled = draft.disabledTools ?? []
    set('disabledTools', enabled
      ? disabled.filter((t) => t !== toolName)
      : [...disabled, toolName])
  }

  const writable = draft.capabilities.some((c) => c !== 'select')

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      if (draft.id === null) {
        await api.create(draft)
      } else {
        await api.update(draft.id, draft)
      }
      onSaved()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const test = async () => {
    setBusy(true)
    setTestResult(null)
    try {
      const result = await api.test(draft)
      setTestResult(result.ok ? 'Connected.' : `Failed: ${result.error}`)
    } catch (e) {
      setTestResult(`Failed: ${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  const capabilities = server?.capabilities ?? Object.keys(CAPABILITY_HELP)

  return (
    <Modal isOpen size="lg" toggle={onClose} scrollable>
      <ModalHeader toggle={onClose}>
        {draft.id === null ? 'New datasource' : `Edit ${datasource.name}`}
      </ModalHeader>
      <ModalBody>
        {error && <Alert color="danger">{error}</Alert>}
        {testResult && <Alert color={testResult.startsWith('Connected') ? 'success' : 'warning'}>{testResult}</Alert>}
        <Form>
          <Row>
            <Col md={6}>
              <FormGroup>
                <Label for="name">Name</Label>
                <Input id="name" value={draft.name} onChange={(e) => set('name', e.target.value)} />
                <FormText>Agents address the datasource by this name. Renaming it invalidates what
                  an agent has learned about this database.</FormText>
              </FormGroup>
            </Col>
            <Col md={6}>
              <FormGroup>
                <Label for="description">Description</Label>
                <Input id="description" value={draft.description ?? ''}
                       onChange={(e) => set('description', e.target.value)} />
              </FormGroup>
            </Col>
          </Row>
          <FormGroup>
            <Label for="jdbcUrl">JDBC URL</Label>
            <Input id="jdbcUrl" value={draft.jdbcUrl} onChange={(e) => set('jdbcUrl', e.target.value)}
                   placeholder="jdbc:postgresql://localhost:5432/mydb" />
          </FormGroup>
          <Row>
            <Col md={4}>
              <FormGroup>
                <Label for="username">Username</Label>
                <Input id="username" value={draft.username ?? ''} onChange={(e) => set('username', e.target.value)} />
              </FormGroup>
            </Col>
            <Col md={4}>
              <FormGroup>
                <Label for="password">Password</Label>
                <Input id="password" type="password" value={draft.password ?? ''}
                       onChange={(e) => set('password', e.target.value)} />
                {datasource.hasPassword && <FormText>Leave blank to keep the stored password.</FormText>}
              </FormGroup>
            </Col>
            <Col md={4}>
              <FormGroup>
                <Label for="driverClass">Driver class (optional)</Label>
                <Input id="driverClass" value={draft.driverClass ?? ''}
                       onChange={(e) => set('driverClass', e.target.value)} />
                <FormText>Ignored for drop-in drivers, which are matched by URL.</FormText>
              </FormGroup>
            </Col>
          </Row>

          <h6 className="text-body-secondary mt-4">Capabilities</h6>
          <p className="small text-body-secondary mb-2">
            What classes of statement this datasource will run. These prevent honest mistakes — they
            are not a security boundary. For a database that matters, give the server a restricted
            database user as well.
          </p>
          {capabilities.map((capability) => (
            <FormGroup check key={capability} className="mb-1">
              <Input id={`cap-${capability}`} type="checkbox"
                     checked={draft.capabilities.includes(capability)}
                     onChange={(e) => toggleCapability(capability, e.target.checked)} />
              <Label for={`cap-${capability}`} check>
                <code>{capability}</code> — {CAPABILITY_HELP[capability] ?? ''}
              </Label>
            </FormGroup>
          ))}
          {!writable && (
            <FormText className="d-block mb-3">
              Read-only: every connection in this datasource's pool is opened read-only at the database.
            </FormText>
          )}

          <h6 className="text-body-secondary mt-4">Enabled Tools</h6>
          <p className="small text-body-secondary mb-2">
            Select which tools are accessible on this datasource. Disabling a tool withholds it from agents.
          </p>
          {ALL_TOOLS.map((tool) => {
            const isEnabled = !(draft.disabledTools ?? []).includes(tool.name)
            return (
              <FormGroup check key={tool.name} className="mb-1">
                <Input id={`tool-${tool.name}`} type="checkbox"
                       checked={isEnabled}
                       onChange={(e) => toggleToolEnabled(tool.name, e.target.checked)} />
                <Label for={`tool-${tool.name}`} check>
                  <code>{tool.name}</code> — {tool.label.split(' — ')[1] ?? ''}
                </Label>
              </FormGroup>
            )
          })}

          <FormGroup className="mt-4">
            <Label for="objectAllowlist">Object allowlist</Label>
            <Input id="objectAllowlist" type="textarea" rows={3} value={draft.objectAllowlist ?? ''}
                   onChange={(e) => set('objectAllowlist', e.target.value)}
                   placeholder={'sales.orders\nstaging.*'} />
            <FormText>
              One entry per line: <code>schema.table</code>, <code>schema.*</code>, or a bare table
              name. Blank means unrestricted. Anything outside the list is hidden from introspection,
              not merely refused.
            </FormText>
          </FormGroup>

          <FormGroup check className="mt-3 mb-3">
            <Input id="enabled" type="checkbox" checked={draft.enabled}
                   onChange={(e) => set('enabled', e.target.checked)} />
            <Label for="enabled" check>Enabled — visible to MCP clients</Label>
          </FormGroup>

          <h6 className="text-body-secondary mt-4">Limits</h6>
          <Row>
            <Col md={3}>
              <FormGroup>
                <Label for="defaultSchema">Default schema</Label>
                <Input id="defaultSchema" value={draft.defaultSchema ?? ''}
                       onChange={(e) => set('defaultSchema', e.target.value)} />
              </FormGroup>
            </Col>
            <Col md={3}>
              <FormGroup>
                <Label for="maxRows">Max rows</Label>
                <Input id="maxRows" type="number" value={draft.maxRows} onChange={num('maxRows')} />
              </FormGroup>
            </Col>
            <Col md={3}>
              <FormGroup>
                <Label for="maxCellChars">Max chars per cell</Label>
                <Input id="maxCellChars" type="number" value={draft.maxCellChars} onChange={num('maxCellChars')} />
              </FormGroup>
            </Col>
            <Col md={3}>
              <FormGroup>
                <Label for="queryTimeoutSeconds">Query timeout (s)</Label>
                <Input id="queryTimeoutSeconds" type="number" value={draft.queryTimeoutSeconds}
                       onChange={num('queryTimeoutSeconds')} />
              </FormGroup>
            </Col>
          </Row>

          <h6 className="text-body-secondary mt-4">Pool</h6>
          <Row>
            <Col md={3}>
              <FormGroup>
                <Label for="maxPoolSize">Max size</Label>
                <Input id="maxPoolSize" type="number" value={draft.maxPoolSize} onChange={num('maxPoolSize')} />
              </FormGroup>
            </Col>
            <Col md={3}>
              <FormGroup>
                <Label for="minIdle">Min idle</Label>
                <Input id="minIdle" type="number" value={draft.minIdle} onChange={num('minIdle')} />
              </FormGroup>
            </Col>
            <Col md={3}>
              <FormGroup>
                <Label for="connectionTimeoutMs">Connect timeout (ms)</Label>
                <Input id="connectionTimeoutMs" type="number" value={draft.connectionTimeoutMs}
                       onChange={num('connectionTimeoutMs')} />
              </FormGroup>
            </Col>
            <Col md={3}>
              <FormGroup>
                <Label for="idleTimeoutMs">Idle timeout (ms)</Label>
                <Input id="idleTimeoutMs" type="number" value={draft.idleTimeoutMs} onChange={num('idleTimeoutMs')} />
              </FormGroup>
            </Col>
          </Row>
          <Row>
            <Col md={6}>
              <FormGroup>
                <Label for="maxLifetimeMs">Max lifetime (ms)</Label>
                <Input id="maxLifetimeMs" type="number" value={draft.maxLifetimeMs} onChange={num('maxLifetimeMs')} />
                <FormText>Also how long session state set by one call can outlive it on a reused
                  connection.</FormText>
              </FormGroup>
            </Col>
            <Col md={6}>
              <FormGroup>
                <Label for="validationQuery">Validation query (optional)</Label>
                <Input id="validationQuery" value={draft.validationQuery ?? ''}
                       onChange={(e) => set('validationQuery', e.target.value)} placeholder="SELECT 1" />
              </FormGroup>
            </Col>
          </Row>
        </Form>
      </ModalBody>
      <ModalFooter>
        <Button color="info" outline className="me-auto" onClick={test} disabled={busy}>
          {busy && <Spinner size="sm" className="me-2" />}Test
        </Button>
        <Button color="secondary" outline onClick={onClose} disabled={busy}>Cancel</Button>
        <Button color="primary" onClick={save} disabled={busy}>Save</Button>
      </ModalFooter>
    </Modal>
  )
}
