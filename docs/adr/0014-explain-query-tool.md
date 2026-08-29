# Dedicated explain_query tool with dialect prefixing

_Extends ADR-0001 (Capability derives tool surface) and ADR-0008 (Structured tools cover reads only)._

## Context

Understanding database execution plans (cardinality estimates, index usage, join strategies) is
essential for query optimization. However, `EXPLAIN` syntax varies significantly across database
engines (PostgreSQL, MySQL, SQLite, SQL Server), and running `EXPLAIN ANALYZE` on write statements
can execute data modifications as a side effect.

## Decision

1. **Dedicated Tool**: A distinct `explain_query` tool is added to the Tool Surface.
2. **Capability & Scope**: Requires `Capability.SELECT`. It accepts a read-only `SELECT` query
   (or non-modifying CTE). Any write statement or data-modifying CTE is strictly refused.
3. **Object Allowlist Enforcement**: All tables and views referenced in the query must satisfy the
   Datasource's Object Allowlist.
4. **Automatic Dialect Prefixing**: The server determines the database engine from JDBC metadata and
   automatically applies the dialect-appropriate explain clause:
   - **PostgreSQL**: `EXPLAIN (FORMAT JSON, ANALYZE ...)` when `analyze=true`, else `EXPLAIN (FORMAT JSON) ...`
   - **MySQL / MariaDB**: `EXPLAIN FORMAT=JSON ...` (or `EXPLAIN ANALYZE ...`)
   - **SQLite**: `EXPLAIN QUERY PLAN ...`
   - **SQL Server / Generic**: Appropriate plan syntax or standard `EXPLAIN ...`
5. **Output**: The execution plan is parsed and returned as JSON.
6. **Tool Surface Derivation**: `explain_query` is exposed whenever at least one enabled Datasource
   carries `Capability.SELECT`.

## Consequences

- Agents do not need to know engine-specific `EXPLAIN` syntax or JSON formatting options.
- Side-effect executions through `EXPLAIN ANALYZE` on write statements are prevented by classification.
- Explain output is returned in a clean, structured JSON format for agent reasoning.
