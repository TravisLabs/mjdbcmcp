# MCP Java SDK over streamable HTTP, on Spring Boot

The server uses the official MCP Java SDK, so that protocol negotiation, framing, lifecycle and
notifications track the MCP specification. It exposes streamable HTTP on a port and runs on Spring
Boot.

Spring Boot provides the infrastructure for an HTTP server, a REST API and a static-asset pipeline
for the Admin Interface, HikariCP connection pooling, configuration binding, validation, and health
endpoints. The SDK's streamable HTTP transport (`HttpServletStreamableServerTransportProvider`) is
registered as a servlet directly.

## Consequences

Stdout is not the transport, so standard structured logging is enabled. Because the transport
streams over an asynchronous servlet request, async support (`setAsyncSupported(true)`) is required
on the servlet registration.

The server has no authentication; the deployment boundary (loopback binding, container scoping, or
an authenticating reverse proxy) provides protection (ADR-0003).

The transport's Origin and Host pinning provides DNS-rebinding protection for browsers. Its defaults
name localhost, so deployments behind a proxy or host alias configure these or disable them explicitly.
