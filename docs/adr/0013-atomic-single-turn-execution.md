# Atomic single-turn execution and script transactions

## Context

In an HTTP-based MCP server without persistent client identity or session authentication,
multi-turn open transactions introduce severe liabilities:
1. **Connection starvation**: An agent holding an open transaction starves the pool of capacity.
2. **State and lock leakage**: Concurrent agents or aborted LLM loops leave database locks held
   until idle timeouts fire.
3. **Context bloat**: Dedicated transaction management tools consume valuable LLM context and
   invite confused multi-turn retry loops.

## Decision

1. **No Open Transactions**: Connections are borrowed from the Datasource's Pool for the duration of
   a single tool call, executed against, and returned immediately. `begin`, `commit`, and `rollback`
   are removed from the Tool Surface.
2. **Atomic Scripts in `raw_execute`**: `raw_execute` accepts a single SQL statement or an atomic
   multi-statement script (e.g. multiple `INSERT` and `UPDATE` statements).
   - The entire payload is parsed and classified before execution. Every statement in the script
     must satisfy the Datasource's Capabilities and Object Allowlist. Any violation refuses the
     entire script before any statement executes.
   - Execution runs atomically on one leased Connection inside `conn.setAutoCommit(false)`.
   - If any statement fails, the server executes `conn.rollback()`, resets autocommit, returns the
     Connection to the Pool, and returns an error specifying which statement failed and that the
     transaction was rolled back.
   - On success, `conn.commit()` is executed, the Connection is returned to the Pool, and a
     per-statement execution breakdown is returned.
   - Explicit `BEGIN;` / `COMMIT;` / `ROLLBACK;` statements in script text are handled transparently.
3. **`raw_query` Remains Single-Statement**: `raw_query` strictly executes a single `SELECT`
   statement, subject to the Datasource's configured `maxRows` row limit.
4. **Per-Datasource Tool Enablement**: The operator may selectively enable or disable individual
   tools per Datasource in configuration and the Admin UI. A tool appears on the global MCP Tool
   Surface if it is enabled on at least one enabled Datasource. Calling a disabled tool against a
   Datasource returns a refusal.

## Consequences

- No connection is ever pinned across turns, eliminating pool capacity degradation and idle sweeps.
- Multi-statement transactional workflows remain fully supported within a single turn.
- Tool count and schema complexity are reduced, minimizing LLM context overhead.
