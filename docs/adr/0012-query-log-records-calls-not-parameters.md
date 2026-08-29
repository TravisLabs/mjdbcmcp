# The query log records calls, not parameter values

Every tool call is recorded — datasource, tool, statement text, duration, outcome, rows returned,
and update counts — into a `query_log` table in the application database. Refusals are recorded
alongside successes. What is deliberately not recorded is the **bound parameter values**.

Parameters are where the personal data is. `SELECT * FROM patient WHERE ssn = ?` is a statement worth
keeping and a value worth not keeping, and the split is exactly the placeholder boundary — which the
server already has, because parameterisation is what it asks agents to do. Storing them would also
punish the agent that did the right thing: an agent that interpolates its literals into the SQL has
them logged, and one that binds them does not. That asymmetry is the correct incentive.

Statement text is kept because a query log without the query is a latency chart, and the operator
question this exists for is "what is the agent actually running against my database". It is
nevertheless agent-written text that may contain literals, so `query-log.store-sql` turns it off and
keeps the timings.

Refusals are recorded because the refusal rate is the signal that matters most and is invisible
otherwise: an agent that hits a Capability wall or an Object Allowlist edge on half its calls is
misconfigured, and nothing else in the system would say so.

## Consequences

**Logging never delays a query.** Records go onto a bounded queue drained by one writer thread, so a
slow SQLite write cannot land on an agent's call path. When the queue is full, records are dropped
and the count is reported in the dashboard — the log undercounting is acceptable, but a dashboard
quietly undercounting is not.

**The log is a retention-capped window, not an audit trail.** It is pruned by age and by row count,
oldest first, and it lives in the same SQLite file as the configuration — so it goes when that volume
goes. Anyone who needs an audit trail in the compliance sense needs it shipped somewhere that is not
this process's own database.

**Statement text may still carry secrets despite the parameter rule.** An agent that writes
`WHERE token = 'abc123'` has put a secret in the SQL, and the server keeps it for the retention
period. That is a reason to point the docs at `store-sql: false`, not a reason to start parsing
literals back out — that would be Classification's problem all over again, with the same dialect
holes and a worse failure mode.

**Running queries are not written down at all.** They live in memory and vanish with the process,
because a query that was running when a process died was not running afterwards, and persisting them
would put ghosts on the dashboard that no code path would ever clear.
