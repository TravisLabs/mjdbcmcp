package com.travislabs.mjdbcmcp.datasource;

import java.util.Locale;
import java.util.Set;

/**
 * One database target — JDBC URL, driver, credentials, Capabilities, Object Allowlist, and
 * per-datasource tool settings. Defined by the operator, never by the agent.
 *
 * <p>Not to be confused with a Connection, which is the live JDBC object borrowed from this
 * Datasource's Pool. The distinction is binding; see CONTEXT.md.
 *
 * <p>{@code password} is plaintext in memory only — the repository encrypts on write and decrypts
 * on read (ADR-0010), and the REST layer never returns it.
 */
public record Datasource(
        Long id,
        String name,
        String description,
        String jdbcUrl,
        String driverClass,
        String username,
        String password,
        Set<Capability> capabilities,
        ObjectAllowlist allowlist,
        Set<String> disabledTools,
        boolean enabled,
        String defaultSchema,
        int maxRows,
        int maxCellChars,
        int queryTimeoutSeconds,
        int maxPoolSize,
        int minIdle,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        String validationQuery) {

    public Datasource {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        allowlist = allowlist == null ? ObjectAllowlist.unrestricted() : allowlist;
        disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
        if (maxRows <= 0) {
            maxRows = 1000;
        }
        if (maxCellChars <= 0) {
            maxCellChars = 4096;
        }
        if (queryTimeoutSeconds <= 0) {
            queryTimeoutSeconds = 30;
        }
        if (maxPoolSize <= 0) {
            maxPoolSize = 5;
        }
        if (minIdle < 0) {
            minIdle = 0;
        }
        if (connectionTimeoutMs <= 0) {
            connectionTimeoutMs = 30_000L;
        }
        if (idleTimeoutMs < 0) {
            idleTimeoutMs = 600_000L;
        }
        if (maxLifetimeMs <= 0) {
            maxLifetimeMs = 1_800_000L;
        }
    }

    public boolean has(Capability capability) {
        return capabilities.contains(capability);
    }

    public boolean isToolDisabled(String toolName) {
        if (toolName == null || disabledTools.isEmpty()) {
            return false;
        }
        return disabledTools.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isToolEnabled(String toolName) {
        return !isToolDisabled(toolName);
    }

    /**
     * True when no granted Capability writes. This is what puts the Connection into read-only mode
     * once at connect time rather than per call (ADR-0001).
     */
    public boolean isReadOnly() {
        return capabilities.stream().noneMatch(Capability::isWrite);
    }

    public boolean hasAnyWriteCapability() {
        return !isReadOnly();
    }

    public Datasource withPassword(String newPassword) {
        return new Datasource(id, name, description, jdbcUrl, driverClass, username, newPassword,
                capabilities, allowlist, disabledTools, enabled, defaultSchema, maxRows,
                maxCellChars, queryTimeoutSeconds, maxPoolSize, minIdle,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, validationQuery);
    }

    public Datasource withId(Long newId) {
        return new Datasource(newId, name, description, jdbcUrl, driverClass, username, password,
                capabilities, allowlist, disabledTools, enabled, defaultSchema, maxRows,
                maxCellChars, queryTimeoutSeconds, maxPoolSize, minIdle,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, validationQuery);
    }
}
