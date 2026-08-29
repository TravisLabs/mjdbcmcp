# Architecture decisions

This directory records the architecture decisions for `mjdbcmcp`.

| ADR | Title |
| --- | --- |
| [0001 Capability derives the tool surface](0001-capability-derives-tool-surface.md) | Derived from the union of Capabilities across enabled Datasources |
| [0002 Many Datasources per server](0002-many-datasources-per-server.md) | Multiple database targets managed dynamically in one server |
| [0003 Accident-resistant, not attack-proof](0003-accident-resistant-not-attack-proof.md) | Threat model and boundary definition |
| [0004 Parser-based Classification](0004-parser-based-classification.md) | Fail-closed statement parsing and classification with JSQLParser |
| [0005 Streamable HTTP on Spring Boot](0005-streamable-http-on-spring-boot.md) | Streamable HTTP transport architecture |
| [0006 Bundled and drop-in drivers](0006-bundled-and-dropin-drivers.md) | Bundled drivers and dynamic drop-in driver loading |
| [0007 Result encoding](0007-result-encoding.md) | Typed JSON payload format with explicit row cap notices and script breakdowns |
| [0008 Structured Tools cover reads only](0008-structured-tools-read-only.md) | Structured query tools are restricted to reads |
| [0009 The Object Allowlist hides as well as refuses](0009-object-allowlist-hides-as-well-as-refuses.md) | Metadata filtering and SQL schema/table scoping |
| [0010 Datasource secrets encrypted at rest](0010-datasource-secrets-encrypted-at-rest.md) | AES-256-GCM encryption for stored credentials |
| [0011 Connections are pooled](0011-connections-are-pooled.md) | HikariCP pool per Datasource |
| [0012 The query log records calls, not parameter values](0012-query-log-records-calls-not-parameters.md) | Non-blocking async execution logging |
| [0013 Atomic single-turn execution and script transactions](0013-atomic-single-turn-execution.md) | Atomic execution, multi-statement scripts, and per-Datasource tool enablement |
| [0014 Dedicated explain_query tool with dialect prefixing](0014-explain-query-tool.md) | Automated dialect-aware query plan generation |

The vocabulary these use is defined in [CONTEXT.md](../../CONTEXT.md) and is binding.