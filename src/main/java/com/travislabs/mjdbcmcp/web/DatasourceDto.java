package com.travislabs.mjdbcmcp.web;

import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.ObjectAllowlist;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wire form of a Datasource. The password only ever travels inbound: responses carry
 * {@code hasPassword} so the Admin Interface can show whether one is set without ever holding it
 * (ADR-0010).
 */
public record DatasourceDto(
        Long id,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{1,64}",
                message = "must be 1-64 characters of letters, digits, dot, dash or underscore")
        String name,
        String description,
        @NotBlank String jdbcUrl,
        String driverClass,
        String username,
        String password,
        Boolean hasPassword,
        List<String> capabilities,
        String objectAllowlist,
        List<String> disabledTools,
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

    public static DatasourceDto of(Datasource d) {
        return new DatasourceDto(d.id(), d.name(), d.description(), d.jdbcUrl(), d.driverClass(),
                d.username(), null, d.password() != null && !d.password().isEmpty(),
                d.capabilities().stream().map(Capability::wireName).sorted().toList(),
                d.allowlist().format(),
                d.disabledTools() == null ? List.of() : d.disabledTools().stream().sorted().toList(),
                d.enabled(), d.defaultSchema(),
                d.maxRows(), d.maxCellChars(), d.queryTimeoutSeconds(),
                d.maxPoolSize(), d.minIdle(), d.connectionTimeoutMs(), d.idleTimeoutMs(),
                d.maxLifetimeMs(), d.validationQuery());
    }

    public Datasource toDomain() {
        return new Datasource(id, name, description, jdbcUrl, driverClass, username, password,
                parseCapabilities(), ObjectAllowlist.parse(objectAllowlist), parseDisabledTools(),
                enabled, defaultSchema, maxRows, maxCellChars, queryTimeoutSeconds,
                maxPoolSize, minIdle, connectionTimeoutMs, idleTimeoutMs,
                maxLifetimeMs, validationQuery);
    }

    private Set<Capability> parseCapabilities() {
        Set<Capability> out = new LinkedHashSet<>();
        if (capabilities == null) {
            return out;
        }
        for (String name : capabilities) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                out.add(Capability.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown capability '" + name + "'. Valid: "
                        + java.util.Arrays.stream(Capability.values()).map(Capability::wireName).toList());
            }
        }
        return out;
    }

    private Set<String> parseDisabledTools() {
        Set<String> out = new LinkedHashSet<>();
        if (disabledTools == null) {
            return out;
        }
        for (String tool : disabledTools) {
            if (tool != null && !tool.isBlank()) {
                out.add(tool.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
