# mjdbcmcp

An MCP server that lets an agent inspect and operate relational databases over JDBC. It speaks
**streamable HTTP**, holds a **HikariCP pool per Datasource**, and is configured from a **web
interface** — Datasources are added, tested and retired at runtime.

What each Datasource may do is a per-Datasource configuration decision: a set of **Capabilities**
(what classes of statement it will run) and an **Object Allowlist** (which schemas and tables it may
name). The vocabulary is in [CONTEXT.md](./CONTEXT.md) and the architectural decisions are in
[docs/adr/](./docs/adr/).

## Requirements

- Java 21
- Node is downloaded by the Gradle build; no local install needed

## Quick start

```bash
./gradlew bootRun
# Admin UI:      http://localhost:8080
# MCP endpoint:  http://localhost:8080/mcp
```

Add a Datasource in the UI, grant it Capabilities, hit **Test**, save. Point an MCP client at the
endpoint:

```json
{
  "mcpServers": {
    "mjdbcmcp": { "type": "http", "url": "http://localhost:8080/mcp" }
  }
}
```

A packaged run:

```bash
./gradlew bootJar
java -jar build/libs/mjdbcmcp-*.jar
```

## Capabilities

Granted per Datasource by the operator, never requested by the agent.

| Capability | Permits |
| --- | --- |
| `select` | Reads. On its own it also makes every pooled connection read-only at the database. |
| `dml` | `INSERT`, `UPDATE`, `DELETE`, `MERGE`, and the transaction tools. |
| `ddl_create` | `CREATE` of any object. |
| `ddl_alter` | `ALTER` of any object. |
| `ddl_drop` | `DROP` of any object, and `TRUNCATE`. |

`TRUNCATE` sits with `DROP` rather than `DML` on purpose: it discards data irrecoverably and has no
`WHERE` clause to get wrong, so "may change rows" should not imply it.

**Capabilities decide which tools exist at all.** A server whose Datasources are all `select`-only
has no `raw_execute` tool, so an agent cannot attempt what is not there. Editing Capabilities
re-derives the surface and notifies connected clients.

## Object Allowlist

One entry per line on a Datasource; blank means unrestricted.

```
sales.orders        one table
staging.*           every table in a schema
orders              unqualified — matched in any schema
"Sales"."Orders"    quoted parts match case-sensitively
```

It constrains every tool regardless of how the statement was produced, **and hides what it excludes
from introspection** — an un-allowlisted table does not appear in `list_tables` and cannot be
described. Refusing without hiding just produces an agent that rephrases a query that was never the
problem.

## Tools

Every tool takes a `datasource` argument naming one of the configured Datasources.

| Tool | Needs | Purpose |
| --- | --- | --- |
| `list_datasources` | — | What exists, with each Datasource's capabilities and scope |
| `database_info` | — | Product, version, driver |
| `list_schemas` | — | Schemas, filtered to the allowlist |
| `list_tables` | — | Tables and views, filtered to the allowlist |
| `describe_table` | — | Columns, primary key, foreign keys, indexes |
| `query` | `select` | Typed reads — table, columns, filters, order. No SQL text |
| `raw_query` | `select` | One read-only statement the agent writes |
| `explain_query` | `select` | Query execution plan as JSON with automated dialect prefixing |
| `raw_execute` | the matching write capability | One statement or atomic multi-statement script that changes data or schema |

Raw SQL is deliberately two tools: the one an agent reaches for states its intent before the
statement is ever parsed, and reads keep the engine-enforced read-only connection under them.

### Classification

`raw_query`, `explain_query` and `raw_execute` parse statements with JSQLParser rather than matching
leading keywords, because leading-keyword matching misreads the SQL agents actually write. A parse
failure is a refusal, and a **data-modifying CTE classifies as its write verb** —
`WITH d AS (DELETE ... RETURNING *) SELECT * FROM d` reads as a `SELECT` and is not one.

Dialect gaps therefore surface as refusals rather than as statements running unclassified. That is
the intended direction.

### Transactions and Scripts

Transactions are strictly atomic within single `raw_execute` calls. Connections are borrowed from the
Pool, executed against, and returned immediately. `raw_execute` accepts atomic multi-statement scripts
(such as multiple `INSERT` and `UPDATE` statements), parsing and validating all statements against
Capabilities and the Object Allowlist before running inside an isolated transaction. If any statement
fails, the transaction is rolled back and an error is returned. No transaction or connection sits open
across turns (ADR-0013).

## Activity dashboard

The **Activity** tab shows what the server is doing and has done.

- **Running now** — every tool call in flight, longest first, with an elapsed time that counts up.
  This is the view for "why is the agent stuck": a query still running after ten seconds is visible
  here and nowhere else.
- **Statistics** over a selectable window — calls, refused/failed rate, p50 and p95 duration, rows
  returned, broken down by datasource and by tool.
- **Refusals by kind** — a high count means an agent is working against the Capabilities or Object
  Allowlist it was given, which is a configuration problem wearing an agent-behaviour costume.
- **Pools** — active, idle and waiting connections per Datasource, and whether a transaction is open.
- **Slowest** and **recent calls**, with the statement text and outcome.

Every tool call is recorded, including refusals. What is **not** recorded is bound parameter values:
`WHERE ssn = ?` keeps the statement and discards the value, which is the split that matters and is
also the incentive to parameterise. Statement text is kept because a query log without the query is
a latency chart; set `mjdbcmcp.query-log.store-sql: false` to keep the timings without the text.

Logging never delays a query: records go onto a bounded queue drained by one writer thread, and if
that queue fills, records are dropped and the dashboard says how many rather than quietly
undercounting. The log is a retention-capped window in the application database, not an audit trail —
see [ADR-0012](docs/adr/0012-query-log-records-calls-not-parameters.md).

```yaml
mjdbcmcp:
  query-log:
    enabled: true
    store-sql: true      # false keeps timings without statement text
    max-sql-chars: 4000
    retention: 7d
    max-rows: 50000
    queue-capacity: 1000
```

## This is accident resistance, not a security boundary

Capabilities and the Object Allowlist are enforced in this server, not by database `GRANT`s. Against
a deliberately hostile instruction — prompt injection arriving through row data, say — a
write-capable Datasource is compromised by definition, because the credentials it holds can do the
damage. The allowlist scopes what SQL may *name*, not what the engine may *reach*: a view, trigger or
cascade still gets there.

**For a database that matters, give the server a database user that cannot do the thing you are
relying on it not doing.** That is the real boundary.

The server has no authentication of its own, so the deployment boundary is load-bearing: it binds to
loopback by default and the container publishes to loopback. Widening either means putting something
that authenticates in front.

## Configuration

`~/.mjdbcmcp/` (override with `MJDBCMCP_CONFIG_DIR`) holds everything the server owns:

| Path | Contents |
| --- | --- |
| `mjdbcmcp.db` | SQLite application database — the Datasource definitions and the query log |
| `secret.key` | AES-256-GCM key for stored passwords; **back this up**, keep it secret |
| `drivers/` | Drop-in JDBC driver JARs, loaded at startup |

Losing `secret.key` while keeping the database means every Datasource password must be re-entered.

PostgreSQL, MySQL, MariaDB, SQL Server, H2 and SQLite drivers are bundled. Anything else goes into
`drivers/` and is picked up on the next restart; because such a driver is loaded by a child
classloader it is reached through URL matching, so leave `driverClass` blank for it.

Server settings live in `src/main/resources/application.yml`. Notable ones:

- `server.address` — loopback by default.
- `mjdbcmcp.mcp.allowed-origins` / `allowed-hosts` — DNS-rebinding protection for browsers on the MCP
  transport. Extend these if you change the port or serve under a hostname; empty turns the check off.
- `mjdbcmcp.mcp.disabled-tools` — withhold tools regardless of Capability, purely to keep their
  definitions out of the agent's context. Narrowing never widens what a Capability permits, and a
  configuration that becomes unreachable this way is reported at startup.

## Container

```bash
podman build -t mjdbcmcp .
podman run -d --name mjdbcmcp -p 127.0.0.1:8080:8080 \
  -v mjdbcmcp_config:/mjdbcmcp_config mjdbcmcp
```

Or `podman compose up -d` using the bundled [`compose.yaml`](./compose.yaml). The image binds to
`0.0.0.0` inside the container (loopback there would be unreachable) and ships with the MCP
Origin/Host check off, because the shipped defaults name `localhost:8080` and would reject every call
from behind a proxy. Set `MJDBCMCP_MCP_ALLOWEDORIGINS` and `MJDBCMCP_MCP_ALLOWEDHOSTS` when a browser
can reach it.

`HEALTHCHECK` is ignored by Podman's default OCI image format; build with `--format docker` to keep
it, or rely on the compose healthcheck.

## Development

```bash
./gradlew bootRun              # backend on :8080
cd frontend && npm run dev     # Vite on :5173, proxying /api to :8080
./gradlew test                 # fast tier: Classification, allowlist resolution
./gradlew integrationTest      # engine tier: real Postgres + MySQL
```

The engine tier skips itself when no container runtime is reachable. On Podman:

```bash
podman machine start
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
```

The admin UI uses the same Mango theme as MangoPersonalAssistant — Bootswatch Flatly/Darkly under an
IBM Plex + mango-palette override, with the same five-way theme switcher. The preference is stored
under the `mpa-theme` localStorage key, shared with MPA.

## Layout

```
src/main/java/com/travislabs/mjdbcmcp/
  config/      AppProperties, MCP transport + server wiring, SPA resource handling
  crypto/      Password encryption at rest
  datasource/  Datasource definitions, Capabilities, Object Allowlist, pools, transactions, drivers
  sql/         Classification, statement execution, result encoding, JDBC metadata reads
  querylog/    Running-query registry, async query log writer, statistics
  mcp/         The tool surface and its derivation
  web/         REST API behind the Admin Interface
frontend/      React + Vite admin interface
docs/adr/      Architecture decision records
```
