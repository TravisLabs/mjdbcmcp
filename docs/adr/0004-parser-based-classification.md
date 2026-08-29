# Classification uses a real parser and fails closed

Agent-written SQL is classified with a SQL grammar library rather than by matching the leading
keyword, because leading-keyword matching misreads the SQL agents actually write: comment preambles,
CTEs, and above all data-modifying CTEs (`WITH d AS (DELETE ... RETURNING *) SELECT * FROM d`), which
read as SELECT and are not. Three rules follow: a parse failure is a refusal rather than a
pass-through, `raw_query` and `explain_query` payloads containing more than one statement are refused,
and a data-modifying CTE classifies as its write verb.

The parser is JSQLParser. The same parse produces the table names the Object Allowlist is checked
against (ADR-0009), so a statement is never classified by one mechanism and scoped by another.

## Consequences

Vendor-specific syntax the parser does not know will be refused rather than executed. That is the
intended direction — the statements a parser chokes on are disproportionately the ones worth
stopping — but it means dialect gaps surface as user-visible refusals, and the fix is always to
teach the parser, never to fail open.

A refusal must say which of the two it was: "this Datasource may not run that class of statement"
and "this statement could not be parsed" send an agent down completely different paths, and
collapsing them into one message produces an agent that retries a Capability problem by rewriting
syntax forever.

Classification is where correctness lives, so it is a pure unit-tested component with no database
behind it — see the testing tiers in AGENTS.md.
