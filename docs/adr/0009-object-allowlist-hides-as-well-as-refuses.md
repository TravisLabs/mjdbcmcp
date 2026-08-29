# The Object Allowlist hides as well as refuses

A Datasource carries an Object Allowlist: the schemas and tables it may name. Empty means
unrestricted. It constrains every tool regardless of how the statement was produced — the tables
JSQLParser finds in agent-written SQL are checked against it, and the table a Structured Tool was
handed is checked against it — and it also filters introspection, so a table that is not on the list
does not appear in `list_tables` and cannot be described.

The filtering half is the decision. Refusing without hiding produces an agent that can see a table in
`list_tables`, cannot read it, and spends its turns rephrasing the query that was never the problem.
Hiding without refusing is worse: it looks like scoping and enforces nothing. Both halves come from
the same list.

It is orthogonal to Capability. One says what may be done, the other says to what — a Datasource with
the DML Capability and an Object Allowlist of `staging.*` may write, but only there.

## Consequences

This scopes what SQL may *name*, not what the engine may ultimately *reach*. A view over an
un-allowlisted table, a trigger, a stored procedure, or a foreign-key cascade all reach objects the
list never mentions. Like Capabilities, it is accident resistance (ADR-0003), and the real boundary
is a database user that cannot see those objects at all.

Because it hides, an allowlist entry naming a table that does not exist is invisible — introspection
simply returns nothing. The Admin Interface resolves the list against the live database when the
operator saves, so a typo surfaces at configuration time rather than as an agent finding an empty
database.

Allowlist resolution is name matching, not SQL evaluation, so it is a pure unit-tested component
alongside Classification: unqualified names resolved against the Datasource's default schema, case
folding per the driver's identifier rules, and quoted identifiers taken literally.
