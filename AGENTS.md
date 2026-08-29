# mjdbcmcp

JDBC MCP server: streamable HTTP transport, a HikariCP pool per Datasource, configured through a web
interface. See `README.md` for what it does and how to run it.

**Read these first and treat them as binding rather than as suggestions:**

- [CONTEXT.md](./CONTEXT.md) — the glossary. Use these words in code, tool names, config keys,
  REST paths, UI labels and docs. Notably **Datasource** is the configured target and **Connection**
  is the live JDBC object; do not swap them.
- [docs/adr/](./docs/adr/) — fourteen decisions recorded for the system.

## Stack

- **Backend**: Java 21, Spring Boot 4.x, MCP Java SDK 2.x, JSQLParser
- **Frontend**: React 18 + Reactstrap, Vite 8, TypeScript, MPA's Mango theme (Bootswatch)
- **Build**: Gradle (Kotlin DSL) — `./gradlew bootJar` produces one self-contained JAR
- **Application database**: SQLite (`org.xerial:sqlite-jdbc`), accessed with `JdbcClient`

## Consequences of the ADRs that are easy to violate accidentally

- **Capabilities are not a security boundary** (ADR-0003). They stop honest mistakes. Never write a
  doc, error message, config comment or UI label that implies the server defends against a hostile
  agent. Refusals say "this datasource is not configured for that", never "access denied".
- **Classification fails closed** (ADR-0004). Unparseable SQL and statement classes the server does
  not recognise are refused. `raw_query` and `explain_query` require single statements; `raw_execute`
  validates all statements in a multi-statement script against Capabilities and the Object Allowlist
  before executing. Refusal kinds stay distinguishable.
- **Never stringify a value to render it** (ADR-0007). NULL, timezone offsets and numeric precision
  all die that way, and the agent reports the result to a user as fact. Every response also states
  rows returned, whether the row cap was reached, and whether any cell was truncated.
- **The Object Allowlist hides as well as refuses** (ADR-0009). A table outside it must not appear in
  introspection. And a statement whose objects the parser cannot enumerate is refused on a scoped
  Datasource — resolved-but-incomplete is the dangerous case, which is why the classifier walks WITH
  bodies itself rather than trusting `TablesNamesFinder` on a data-modifying CTE.
- **Transactions are atomic within single tool calls** (ADR-0013). Connections are never pinned across
  turns. Multi-statement scripts in `raw_execute` execute inside an isolated transaction that commits
  on success or rolls back on error before returning the connection to the pool.
- **`setReadOnly(true)` is not uniformly honoured** (ADR-0001). pgjdbc needs `readOnlyMode=always` or
  the flag applies only inside explicit transactions. Ask the same question of any driver added.
- **The query log never stores bound parameters** (ADR-0012), and it never sits on a query's path:
  records go through a bounded queue, and a full queue drops records and counts them rather than
  making an agent wait. A dropped count that is not surfaced is the same silent-undercount failure as
  a truncated result that does not say so.

## Other decisions worth knowing

### MCP transport is a servlet, not a Spring MVC handler

SDK 2.x folded the servlet transports into `mcp-core` and dropped the separate `mcp-spring-webmvc`
artifact. `HttpServletStreamableServerTransportProvider` is registered as a `ServletRegistrationBean`
in `McpServerConfig`. **`setAsyncSupported(true)` is required** — the transport streams over an async
request and the container rejects `startAsync()` without it.

### The tool surface is derived, not configured

`ToolSurface` computes it from the union of Capabilities across enabled Datasources and syncs it onto
the running `McpSyncServer` after any Datasource write, then notifies clients. Adding a tool means
adding it there as well as in `DatabaseTools`.

### Every tool call is logged, and there is one place that can do it

`DatabaseTools.tool(...)` wraps every handler: it starts the query-log handle, renders the payload,
attaches the transaction notice and finishes the record. Handlers return a `Reply` (datasource +
payload map) rather than a finished `CallToolResult` precisely so a tool added later cannot return
from anywhere else and quietly escape logging. Row counts reach the log because they are already keys
in that payload map — `rowCount`, `rowCapReached`, `updateCount`.

In-flight calls live in memory in `QueryLogService` and are never persisted; finished ones go to
SQLite through the writer thread.

### Pools are keyed by Datasource name and evicted on write

`PoolRegistry` creates a `HikariDataSource` lazily on first use. Any create/update/delete evicts the
pool and discards that Datasource's transaction. `probe()` opens a throwaway pool of one for the UI's
Test button without disturbing the live pools.

### Schema changes go in `db/schema.sql`

`spring.sql.init` replays that file on every boot, so every statement must be idempotent
(`CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`). There is no Flyway: its SQLite module
ships under Redgate's non-commercial licence.

### The frontend theme is MangoPersonalAssistant's, ported not forked

`style.css` (everything above the "mjdbcmcp additions" marker), `context/ThemeContext.tsx`,
`components/ThemeSwitcher.tsx` and the theme glyphs in `components/icons.tsx` come from
`../MangoPersonalAssistant/frontend`. **The `mpa-`/`mango-`/`data-mpa-theme` names are kept
deliberately** so an update over there re-ports as a straight overwrite — do not rename them. Five
preferences share the `mpa-theme` localStorage key, also on purpose. Never hardcode a colour or use
`text-white-*`/`btn-light`: they break under the contrast and colour-blind palettes. Use `--bs-*` role
variables and outline buttons. IBM Plex is self-hosted from `frontend/public/fonts`.

## Testing

Two tiers.

**`./gradlew test`** — Classification and Object Allowlist resolution, pure, no database. That is
where correctness lives, so cover data-modifying CTEs, comment preambles and multi-statement payloads
exhaustively.

**`./gradlew integrationTest`** — real Postgres and MySQL via Testcontainers, for what only an engine
can prove: read-only Connection enforcement, `setMaxRows`, idle-transaction rollback and
`DatabaseMetaData` shape. **H2 is deliberately not used there** — its dialect and metadata differ from
real engines precisely where this server is most likely to be wrong, and it would have hidden the
pgjdbc `readOnlyMode` bug. The tier skips itself when no container runtime is reachable.

Podman, not Docker, on this machine:

```bash
podman machine start
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
```

## Conventions

- The REST layer never returns a stored password; `DatasourceDto` carries `hasPassword` instead, and
  a blank password on an edit means "keep the existing one".
- Config dir is created in `main()` before the context starts, because SQLite will not create the
  parent directory of its database file.

## Verify

```bash
./gradlew test             # fast tier
./gradlew integrationTest  # engine tier (needs a container runtime)
./gradlew bootJar          # full build including the frontend
```
