# Results declare columns once and return rows as arrays

A result names its columns and their SQL types once, then returns rows as positional arrays rather
than as objects keyed by column name. Row objects repeat every column name on every row, which for a
wide result is a large amount of an agent's context spent on nothing. Values are typed rather than
stringified: SQL NULL is JSON null and never the text `"NULL"`, temporal values are ISO-8601 with the
offset preserved rather than rendered in the server's zone, numerics beyond double precision are
strings rather than silently rounded, and binary is base64.

Result size is bounded with `Statement.setMaxRows` and a per-cell truncation limit, streaming with
`setFetchSize` so the driver never materializes a large table. `setMaxRows` was chosen over injecting
a `LIMIT` clause because it needs no SQL rewriting and so avoids `LIMIT` versus `TOP` versus
`FETCH FIRST` versus `ROWNUM` entirely.

## Consequences

Every response states rows returned, whether the row cap was reached, and whether any cell was
truncated. When `rowCapReached` is true, an explicit notice (`message: "Result set limit reached (capped at N rows)."`)
is included in the payload. Silent truncation is how an agent concludes a table has a hundred rows
and reports that to a user as fact.

For multi-statement scripts in `raw_execute` (ADR-0013), the payload returns an overall `committed: true`,
`totalUpdateCount`, and a `statements` array giving the per-statement execution details (verb, updateCount,
or returning result rows).

"Never stringify a value to render it" is the rule that is easiest to violate by accident, because
`toString()` on a driver's value object usually produces something that looks right. It is wrong for
exactly the values that matter: a `TIMESTAMP WITH TIME ZONE` rendered through the server's default
zone is a different instant, and a `NUMERIC(38,10)` through a double is a different number.
