package com.travislabs.mjdbcmcp.datasource;

import com.travislabs.mjdbcmcp.Refusal;
import com.travislabs.mjdbcmcp.sql.Classification;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Owns the lifecycle of Datasources and the checks every tool call passes through: the Datasource
 * exists and is enabled, the tool is enabled on it, its Capabilities cover the statement, and its
 * Object Allowlist covers the objects named.
 *
 * <p>Any write to a Datasource evicts its Pool, so the next call picks up the new settings.
 */
@Service
public class DatasourceService {

    private final DatasourceRepository repository;
    private final PoolRegistry pools;

    public DatasourceService(DatasourceRepository repository, PoolRegistry pools) {
        this.repository = repository;
        this.pools = pools;
    }

    public List<Datasource> findAll() {
        return repository.findAll();
    }

    public List<Datasource> findEnabled() {
        return repository.findAll().stream().filter(Datasource::enabled).toList();
    }

    public Optional<Datasource> findById(long id) {
        return repository.findById(id);
    }

    /** The Capabilities granted anywhere, which is what the tool surface is derived from (ADR-0001). */
    public Set<Capability> grantedCapabilities() {
        return findEnabled().stream()
                .flatMap(d -> d.capabilities().stream())
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(Capability.class)));
    }

    public Datasource requireEnabled(String name) {
        Datasource d = repository.findByName(name).orElseThrow(() -> new Refusal(
                Refusal.Kind.UNKNOWN_DATASOURCE,
                "No datasource named '" + name + "'. Configured datasources: " + names() + "."));
        if (!d.enabled()) {
            throw new Refusal(Refusal.Kind.DATASOURCE_DISABLED,
                    "Datasource '" + name + "' is currently disabled by the operator.");
        }
        return d;
    }

    /** Refuses if the operator explicitly disabled the tool on this Datasource (ADR-0013). */
    public void requireToolEnabled(Datasource d, String toolName) {
        if (d.isToolDisabled(toolName)) {
            throw new Refusal(Refusal.Kind.DISABLED_TOOL,
                    "Tool '" + toolName + "' is disabled on datasource '" + d.name() + "'.");
        }
    }

    /** Refuses unless the Datasource carries the Capability the statement needs (ADR-0001). */
    public void requireCapability(Datasource d, Capability required, String what) {
        if (!d.has(required)) {
            throw new Refusal(Refusal.Kind.CAPABILITY,
                    "Datasource '" + d.name() + "' is not configured to run " + what + " (needs the "
                            + required.wireName() + " capability; it has "
                            + (d.capabilities().isEmpty() ? "none" : Capability.format(d.capabilities()))
                            + "). This is a configuration setting, not something to work around by "
                            + "rewriting the statement.");
        }
    }

    /** Refuses unless every object the statement names is on the Datasource's Object Allowlist. */
    public void requireAllowlisted(Datasource d, Classification classification) {
        if (d.allowlist().isUnrestricted()) {
            return;
        }
        if (!classification.tablesResolved()) {
            // The scope cannot be honoured on a statement whose objects are unknown, and letting it
            // through unchecked would make the allowlist a suggestion.
            throw new Refusal(Refusal.Kind.NOT_ALLOWLISTED,
                    "Datasource '" + d.name() + "' is scoped to " + d.allowlist()
                            + ", and the objects this " + classification.verb()
                            + " statement names could not be determined, so it was not run.");
        }
        requireAllowlisted(d, classification.tables());
    }

    private void requireAllowlisted(Datasource d, List<Classification.QualifiedName> tables) {
        List<String> refused = tables.stream()
                .filter(t -> !d.allowlist().permitsTable(
                        ObjectAllowlist.resolveSchema(t.schema(), d.defaultSchema()), t.table()))
                .map(Classification.QualifiedName::toString)
                .toList();
        if (!refused.isEmpty()) {
            throw new Refusal(Refusal.Kind.NOT_ALLOWLISTED,
                    "Datasource '" + d.name() + "' is scoped to " + d.allowlist()
                            + ", which does not include " + String.join(", ", refused) + ".");
        }
    }

    /** Same check for a Structured Tool, which was handed a name rather than SQL. */
    public void requireAllowlisted(Datasource d, String schema, String table) {
        if (d.allowlist().isUnrestricted()) {
            return;
        }
        requireAllowlisted(d, List.of(new Classification.QualifiedName(schema, table)));
    }

    /**
     * Borrows a Connection from the Pool for one tool call. Closing the lease returns it to the
     * pool immediately (ADR-0013).
     */
    public Lease lease(Datasource d) {
        try {
            return new Lease(pools.pool(d).getConnection());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not get a connection from datasource '" + d.name() + "': " + e.getMessage(), e);
        }
    }

    public Datasource create(Datasource d) {
        repository.findByName(d.name()).ifPresent(existing -> {
            throw new IllegalArgumentException("A datasource named '" + d.name() + "' already exists");
        });
        return repository.insert(d);
    }

    public Datasource update(long id, Datasource incoming) {
        Datasource existing = repository.findById(id).orElseThrow(
                () -> new java.util.NoSuchElementException("No datasource with id " + id));
        // A blank password on an edit means "leave it alone" — the REST layer never sends the
        // current one back, so blank cannot be distinguished from unchanged any other way.
        Datasource merged = incoming.password() == null || incoming.password().isEmpty()
                ? incoming.withPassword(existing.password())
                : incoming;
        repository.update(id, merged);
        invalidate(existing.name());
        invalidate(merged.name());
        return merged.withId(id);
    }

    public void delete(long id) {
        repository.findById(id).ifPresent(d -> {
            repository.delete(id);
            invalidate(d.name());
        });
    }

    public void probe(Datasource d) throws Exception {
        pools.probe(d);
    }

    public List<PoolStats> poolStats() {
        return pools.stats();
    }

    private void invalidate(String name) {
        pools.evict(name);
    }

    private String names() {
        List<Datasource> all = findEnabled();
        return all.isEmpty() ? "(none configured)"
                : all.stream().map(Datasource::name).collect(Collectors.joining(", "));
    }

    /** A borrowed Connection that returns to the Pool upon close. */
    public record Lease(Connection connection) implements AutoCloseable {
        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Returning to the pool; a failure here is the pool's to handle.
            }
        }
    }
}
