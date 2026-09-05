package com.travislabs.mjdbcmcp.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application configuration. {@code config-dir} is the one directory the server owns: it holds the
 * SQLite application database, the encryption key for stored Datasource passwords, and the
 * {@code drivers/} drop-in directory.
 */
@ConfigurationProperties("mjdbcmcp")
public record AppProperties(Path configDir, Mcp mcp, QueryLog queryLog) {

    public AppProperties {
        queryLog = queryLog == null ? QueryLog.defaults() : queryLog;
    }

    /**
     * Resolves the {@code drivers/} directory within the application configuration directory.
     *
     * @return path to the drop-in drivers directory
     */
    public Path driversDir() {
        return configDir.resolve("drivers");
    }

    /**
     * Resolves the {@code secret.key} file within the application configuration directory.
     *
     * @return path to the encryption key file
     */
    public Path secretKeyFile() {
        return configDir.resolve("secret.key");
    }

    /**
     * @param endpoint       path the streamable HTTP transport is served on
     * @param serverName     name reported to MCP clients during initialization
     * @param allowedOrigins accepted {@code Origin} headers; empty disables the check
     * @param allowedHosts   accepted {@code Host} headers; empty disables the check
     * @param requestTimeout how long a single MCP request may take
     * @param disabledTools  tools withheld regardless of Capability, purely to keep their definitions
     *                       out of the agent's context. Narrowing never widens what a Capability
     *                       permits (ADR-0001).
     */
    public record Mcp(
            String endpoint,
            String serverName,
            List<String> allowedOrigins,
            List<String> allowedHosts,
            Duration requestTimeout,
            List<String> disabledTools) {

        public Mcp {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
            disabledTools = disabledTools == null ? List.of() : List.copyOf(disabledTools);
        }
    }

    /**
     * @param enabled       false stops recording entirely; the running-query view still works,
     *                      because that is in-memory and costs nothing
     * @param storeSql      whether the statement text is kept. Agent-written SQL can carry literal
     *                      values, so an operator who does not want those at rest turns this off and
     *                      keeps the timings (ADR-0012)
     * @param maxSqlChars   statement text longer than this is stored truncated
     * @param retention     how long a record is kept
     * @param maxRows       hard cap on the table, enforced oldest-first regardless of retention
     * @param queueCapacity in-flight records awaiting the writer thread. Full means records are
     *                      dropped rather than a query being made to wait; drops are counted
     */
    public record QueryLog(
            boolean enabled,
            boolean storeSql,
            int maxSqlChars,
            Duration retention,
            int maxRows,
            int queueCapacity) {

        public QueryLog {
            maxSqlChars = maxSqlChars <= 0 ? 4000 : maxSqlChars;
            retention = retention == null ? Duration.ofDays(7) : retention;
            maxRows = maxRows <= 0 ? 50_000 : maxRows;
            queueCapacity = queueCapacity <= 0 ? 1000 : queueCapacity;
        }

        static QueryLog defaults() {
            return new QueryLog(true, true, 4000, Duration.ofDays(7), 50_000, 1000);
        }
    }
}
