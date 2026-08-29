# Structured Tools cover reads only; writes go through raw SQL

The structured tool surface is introspection and parameterized reads — list schemas, list tables,
describe a table, query with typed filters. There are deliberately no `insert_row`, `create_table`,
or `alter_table` tools. Structured DDL in particular would require a cross-vendor column type and
constraint model (`SERIAL` versus `IDENTITY` versus `AUTO_INCREMENT`, `TEXT` versus `VARCHAR2`), which
is a large surface that would be wrong for someone's dialect on day one. Keeping writes in
`raw_execute` also keeps Classification the single place write intent is decided, and keeps the tool
payload small.

Introspection reads through JDBC `DatabaseMetaData` rather than `information_schema` or vendor
catalogs, so that any driver works on day one — the premise of a generic JDBC server. Vendor modules
may enrich this later with what the standard API omits, such as check constraints, view definitions,
and comments.

## Consequences

`DatabaseMetaData` quality varies by driver and will be thinner than a catalog query on every vendor;
that is accepted in exchange for universal coverage. Someone will eventually propose replacing it
with `information_schema` — note that Oracle and SQLite have none.

The structured `query` tool builds its own statement, so it never goes through Classification: it is
a select by construction and carries the select Capability directly. Its identifiers are quoted with
the driver's own identifier quote string and checked against the Object Allowlist by name, which is
what lets it accept a table name without accepting SQL.
