# Accident-resistant, not attack-proof

Capabilities are enforced in the server (Classification plus a read-only Connection for reads), not
by per-Capability database users with real GRANTs. This keeps the server usable against a database
the operator was simply handed, at the cost of a weaker guarantee: against a deliberately hostile
instruction — prompt injection arriving through row data, say — a write-capable Datasource is
compromised by definition, because the credentials it holds can do the damage and no Java-side
Classification survives a determined attacker across vendor SQL dialects.

Capabilities therefore exist to prevent honest mistakes and to narrow what a well-behaved agent
attempts. They are not a security boundary. The same is true of the Object Allowlist: it stops an
agent naming a table it should not, not an engine reaching one through a view or a trigger.

The addition: this server listens on a port. That makes it reachable by things that are not the
agent, so the deployment boundary — loopback binding, a published port scoped to a host, an
authenticating proxy — is load-bearing in a way it never was for a stdio server whose only client
was the process that spawned it. There is no authentication in the server itself.

## Consequences

The README states this plainly, and recommends a restricted database user as the real boundary for
anyone exposing a Datasource that matters. Nobody should reach "read-only against production" by
reading the config schema and assuming.

No doc, error message, config comment or UI label may imply the server defends against a hostile
agent. When a refusal is written, it is written as "this Datasource is not configured for that", not
as "access denied".

Because the port is the exposure, the shipped defaults bind to loopback and the container publishes
to loopback. Widening either is an operator decision that the docs attach the proxy requirement to.
