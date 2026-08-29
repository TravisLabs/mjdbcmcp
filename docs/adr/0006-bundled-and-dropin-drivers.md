# Drivers are bundled, with a drop-in directory

The server bundles drivers for PostgreSQL, MySQL, MariaDB, SQL Server, H2 and SQLite, and loads any
other driver JAR the operator places in `${config-dir}/drivers` at startup.

Common database drivers are bundled directly in the application JAR. For proprietary or specialized
databases, the operator drops the JDBC driver JAR into `${config-dir}/drivers/` and restarts the
server. A delegating `DriverShim` registered under the application classloader ensures `DriverManager`
resolves drop-in drivers seamlessly via URL matching.

## Consequences

A drop-in driver is matched by URL rather than by class name, because the shim is what
`DriverManager` sees. A Datasource may therefore not pin `driverClass` for a drop-in driver; the
field is honoured only when the application classloader can resolve it, and silently ignored
otherwise. That asymmetry is confusing enough to belong in the UI help text, not just here.

Bundled driver versions are now this project's problem: they are BOM-managed by Spring Boot and move
when Boot moves, which can change behaviour against a database nobody tested. The drop-in directory
is the escape hatch — an operator who needs a specific version drops that JAR in, though it will not
displace the bundled one for URLs the bundled one also claims.

Adding a driver requires a restart. Making the drop-in directory hot-reloadable would mean unloading
classloaders holding live pools, which is not worth it.
