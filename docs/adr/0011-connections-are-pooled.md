# Connections are pooled

Every Datasource is backed by a HikariCP pool, created on first use and closed when the Datasource is
edited or deleted. A tool call borrows a Connection, uses it, and returns it. Pool size, minimum
idle, connection timeout, idle timeout, maximum lifetime and an optional validation query are
per-Datasource settings.

This server holds many Datasources (ADR-0002) and answers concurrent HTTP requests (ADR-0005)
against databases where connection setup (TCP, TLS, authentication) dominates query latency.
Pooling is therefore a core architectural requirement.

Physical connections outlive individual tool calls and are reused across requests.

## Consequences

**Session state leaks between calls.** HikariCP resets autocommit, read-only, isolation, catalog and
schema when a Connection is returned, but nothing resets `SET search_path`, a session variable, a
temporary table or a session-scoped role. A statement that changes session state changes it for
whoever borrows that physical connection next — which, since this server has no per-client identity
(ADR-0003), may be a different agent. This is a real sharp edge and it is not defended against; an
operator who cares sets `maxLifetime` low or gives the Datasource a validation query that resets what
matters.

**Read-only is a pool setting, not a call setting.** ADR-0001 requires read-only to be set once at
connect time rather than per tool call, which lands naturally here: it is configured on the pool, so
every physical connection is created read-only and no code path can forget. It also means the
read-only backstop cannot be toggled for a single call, which is the intended rigidity.

**Transactions do not pin connections across turns.** Under ADR-0013, transactions are atomic
within single `raw_execute` calls. A connection is borrowed for that call, executed, committed or rolled
back, and returned immediately to the pool. There are no long-lived pinned connections and no idle sweeps.

**A pool is a standing resource against someone else's database.** Minimum-idle connections sit open
whether or not an agent is working, and appear in the DBA's connection count. `minIdle` defaults to
zero so an idle Datasource costs nothing, which is the right default for a server whose Datasources
are mostly idle mostly of the time.

**Pool exhaustion is per Datasource, and so is the blame.** A Datasource whose queries are slow
blocks only its own callers, and `connectionTimeout` bounds the wait rather than letting a tool call
hang until the agent's own timeout fires.
