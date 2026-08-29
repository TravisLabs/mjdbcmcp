# Many Datasources per server, configured at runtime

One server process holds many Datasources, each named, and every tool takes a `datasource` argument.
The operator adds, tests, edits and retires them from the Admin Interface while the server is
running; nothing is restarted and no MCP client configuration changes.

This server speaks streamable HTTP (ADR-0005) and is deployed as a long-lived service, often in a
container, where one process per database would multiply published ports, image instances, and
healthchecks. The core design requirement is that database targets can be managed dynamically from
a web interface at runtime.

Serving several Datasources means the exposed tool surface is derived from the union of their
Capabilities, so `list_datasources` reports each Datasource's Capabilities and tools, and each call
is re-checked against the target Datasource (ADR-0001).

## Consequences

Every tool takes a `datasource` argument, and an agent that omits it or names one that does not exist
gets a refusal naming the ones that do. Cross-Datasource work is still not the server's problem: no
tool reads from two Datasources, and there is no distributed transaction.

A Datasource name is part of the agent-visible contract. Renaming one is not a cosmetic edit — it
invalidates whatever the agent has learned about that database. The Admin Interface treats a rename
as what it is.

Pool exhaustion is now a shared-fate problem: a Datasource with a large pool and slow queries can
starve the container of memory or file descriptors while other Datasources are idle. Pool sizes are
therefore per-Datasource settings the operator sets deliberately, not a global default.
