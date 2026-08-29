# mjdbcmcp

An MCP server that lets an agent inspect and operate relational databases over JDBC. It exists to
put a database behind a tool surface an agent can use safely, where "safely" is a per-Datasource
configuration decision rather than a property of the server.

The language below is **binding**: use these words in code, tool names, config keys, REST paths, UI
labels and docs. The architectural decisions behind them are documented in [docs/adr/](./docs/adr/).

## Language

### Configuration

**Datasource**:
One database target — JDBC URL, driver, credentials, and the Capabilities and Object Allowlist
granted to it. Defined by the operator, never by the agent. Two databases means two Datasources.
_Avoid_: connection (means the live JDBC object), profile, target, database

**Connection**:
A live JDBC connection, borrowed from a Datasource's Pool for the duration of one tool call and
returned immediately afterwards. Connections are never pinned across turns (ADR-0013).
_Avoid_: session

**Pool**:
The HikariCP pool backing one Datasource. Created on first use, closed when the Datasource is edited
or deleted. Sized per Datasource by the operator. The agent never sees it, but it is what makes
Connection state shared across calls (ADR-0011).
_Avoid_: connection pool (redundant), datasource (that is the configuration)

**Transaction**:
An atomic unit of work executed entirely within a single tool call (e.g. a multi-statement script in
`raw_execute`). Begun, committed or rolled back within that single call; no transaction sits open
across tool calls (ADR-0013).
_Avoid_: session, unit of work, batch

**Capability**:
A permission attached to a Datasource, naming a class of statement it may run: select, DML, DDL
create, DDL alter, DDL drop. Granted by the operator, never requested by the agent.
_Avoid_: permission, mode, privilege, grant

**Object Allowlist**:
The set of schemas and tables a Datasource may name, constraining every tool regardless of how the
statement was produced, and hiding everything else from introspection. Orthogonal to Capability —
one says what may be done, the other says to what. Scopes what SQL may name, not what the engine may
ultimately reach.
_Avoid_: whitelist, scope, filter

**Tool Surface**:
The set of tools this server exposes. Derived from the union of the Capabilities granted across all
enabled Datasources, so a server holding only select-only Datasources has no `raw_execute` tool at
all.
_Avoid_: tool list, API, capabilities (means the statement classes above)

### Tools

**Structured Tool**:
A tool that takes typed arguments naming tables, columns, and predicates, and builds the statement
itself. The agent never supplies SQL text.
_Avoid_: safe tool, high-level tool, builder

**Raw SQL Tool**:
A tool that accepts SQL text written by the agent. Subject to the same Capability and Object
Allowlist checks as a Structured Tool, but the server must first work out what the statement does.
_Avoid_: query tool, passthrough, escape hatch

**Classification**:
Determining which Capability a piece of agent-written SQL requires, so it can be permitted or
refused. Only meaningful for the Raw SQL Tool — a Structured Tool knows what it built.
_Avoid_: parsing, validation, SQL inspection

### Operation

**Admin Interface**:
The web UI and the REST API behind it, where the operator creates and edits Datasources. The only
place configuration is written; the agent's tool surface never mutates it.
_Avoid_: dashboard, console, settings page

**Drop-in Driver**:
A JDBC driver JAR the operator places in the driver directory for a vendor the server does not
bundle. Loaded at startup and reached through URL matching rather than by class name (ADR-0006).
_Avoid_: plugin, external driver, custom driver
