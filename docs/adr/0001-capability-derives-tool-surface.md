# Capability derives the tool surface

A Datasource is granted Capabilities (select, DML, DDL create, DDL alter, DDL drop) by the operator,
and the set of tools the server exposes is derived from them rather than configured separately — a
server whose Datasources are all select-only has no `raw_execute` tool at all, so the agent cannot
attempt what is not there and unused tool definitions do not consume its context. Raw SQL is split
into two tools for this reason: `raw_query` carries only the select Capability while `raw_execute`
carries the write ones, so the tool an agent reaches for states its intent before Classification ever
runs.

Because the surface is shared across Datasources, a tool being present no longer proves the named
Datasource permits it. `raw_execute` therefore re-checks the Capability against the Datasource named
in the call and refuses with the Capability it wanted; `list_datasources` reports each Datasource's
Capabilities so a well-behaved agent can choose correctly rather than probe.

Read-only is set on the Connection once, at connect time, when the Datasource has only the select
Capability — not per tool call. `setReadOnly` cannot be called inside an active transaction, and a
second read-only Connection would hide the agent's own uncommitted writes from its own queries. A
select-only Datasource can never have a write transaction open, so the engine-enforced backstop is
never weakened where it is load-bearing. Because Connections are pooled (ADR-0011), "at connect time"
means on the pool: every physical connection is created read-only, so no code path can forget to set
it and no call can toggle it.

Capabilities set the maximum surface; an operator may narrow it further by disabling individual
tools, purely to keep their definitions out of the agent's context. Narrowing never widens what a
Capability permits.

## Consequences

Because writes are reachable only through `raw_execute`, granting a write Capability while disabling
the raw SQL tool produces a Datasource that appears write-capable in config and is read-only in
practice. The server says so at startup, and the Admin Interface says so at the point of editing,
rather than letting it be discovered by an agent's failed attempt.

The two-tool split for raw SQL is the part a reader is most likely to want to "simplify" back into
one. Don't: merging them removes the engine-enforced backstop on reads and makes Classification the
only thing preventing a data-modifying CTE from running under a read-shaped tool.

Editing a Datasource's Capabilities changes the tool surface, so the server re-derives it and sends
`notifications/tools/list_changed`. Clients that cache the tool list across a config edit will be
stale until they honour that notification.

**`setReadOnly(true)` does not mean what this decision assumes on every driver, and the gap is
silent.** pgjdbc defaults `readOnlyMode` to `transaction`, which applies the flag only inside an
explicitly begun transaction — so in autocommit, which is every statement outside the transaction
tools, a "read-only" connection executes an INSERT happily while `isReadOnly()` returns true.
`PoolRegistry` therefore sets `readOnlyMode=always` for read-only PostgreSQL Datasources. Any driver
added later needs the same question asked of it, because the failure mode is a backstop that reports
itself as present and is not. This was found by the engine testing tier and is invisible to H2, which
is the argument for that tier in one example.
