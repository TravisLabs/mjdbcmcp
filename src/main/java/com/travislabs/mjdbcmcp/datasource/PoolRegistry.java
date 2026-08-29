package com.travislabs.mjdbcmcp.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One HikariCP pool per Datasource, created lazily on first use and evicted whenever the definition
 * changes (ADR-0011). Pools are keyed by Datasource name, which is unique in the schema.
 */
@Component
public class PoolRegistry {

    private static final Logger log = LoggerFactory.getLogger(PoolRegistry.class);

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final DriverRegistry drivers;

    public PoolRegistry(DriverRegistry drivers) {
        this.drivers = drivers;
    }

    public HikariDataSource pool(Datasource d) {
        return pools.computeIfAbsent(d.name(), name -> create(d));
    }

    /** Closes and forgets the pool for a Datasource. Safe to call for a name that has no pool. */
    public void evict(String name) {
        HikariDataSource ds = pools.remove(name);
        if (ds != null) {
            log.info("Closing pool for datasource '{}'", name);
            ds.close();
        }
    }

    public List<PoolStats> stats() {
        List<PoolStats> out = new ArrayList<>();
        pools.forEach((name, ds) -> {
            var mx = ds.getHikariPoolMXBean();
            out.add(mx == null
                    ? new PoolStats(name, 0, 0, 0, 0, ds.getMaximumPoolSize())
                    : new PoolStats(name, mx.getTotalConnections(), mx.getActiveConnections(),
                            mx.getIdleConnections(), mx.getThreadsAwaitingConnection(), ds.getMaximumPoolSize()));
        });
        out.sort((a, b) -> a.datasource().compareTo(b.datasource()));
        return out;
    }

    /** Opens a throwaway pool of one to prove a definition works, without touching the live pools. */
    public void probe(Datasource d) throws Exception {
        HikariConfig cfg = baseConfig(d);
        cfg.setPoolName("probe-" + d.name());
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        cfg.setInitializationFailTimeout(1);
        try (HikariDataSource ds = new HikariDataSource(cfg); var conn = ds.getConnection()) {
            conn.getMetaData().getDatabaseProductName();
        }
    }

    private HikariDataSource create(Datasource d) {
        log.info("Opening pool for datasource '{}' ({}), capabilities {}",
                d.name(), d.jdbcUrl(), Capability.format(d.capabilities()));
        return new HikariDataSource(baseConfig(d));
    }

    private HikariConfig baseConfig(Datasource d) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("mcp-" + d.name());
        cfg.setJdbcUrl(d.jdbcUrl());
        cfg.setUsername(d.username());
        cfg.setPassword(d.password());
        // Only pin the driver class when this classloader can see it; drop-in drivers are reached
        // through DriverManager URL matching instead (ADR-0006).
        drivers.resolvable(d.driverClass()).ifPresent(cfg::setDriverClassName);
        cfg.setMaximumPoolSize(d.maxPoolSize());
        cfg.setMinimumIdle(Math.min(d.minIdle(), d.maxPoolSize()));
        cfg.setConnectionTimeout(d.connectionTimeoutMs());
        cfg.setIdleTimeout(d.idleTimeoutMs());
        cfg.setMaxLifetime(d.maxLifetimeMs());
        // ADR-0001: read-only is decided once, on the pool, so every physical connection is created
        // that way and no call can toggle it. A Datasource with any write Capability is not read-only
        // even for a read, because setReadOnly cannot be changed inside an open transaction.
        cfg.setReadOnly(d.isReadOnly());
        if (d.isReadOnly()) {
            applyReadOnlyDialectQuirks(cfg, d);
        }
        if (d.validationQuery() != null && !d.validationQuery().isBlank()) {
            cfg.setConnectionTestQuery(d.validationQuery());
        }
        if (d.defaultSchema() != null && !d.defaultSchema().isBlank()) {
            cfg.setSchema(d.defaultSchema());
        }
        return cfg;
    }

    /**
     * Makes {@code setReadOnly(true)} mean what ADR-0001 assumes it means.
     *
     * <p>On pgjdbc it does not, by default: {@code readOnlyMode} defaults to {@code transaction},
     * which applies the flag only inside an explicitly begun transaction. In autocommit — which is
     * every statement outside the transaction tools — a read-only connection happily executes an
     * INSERT. {@code always} issues the session-level setting instead, so the engine refuses.
     *
     * <p>This is the class of thing the engine testing tier exists to catch; it does not show up
     * against H2, which is why H2 is not used there.
     */
    private void applyReadOnlyDialectQuirks(HikariConfig cfg, Datasource d) {
        String url = d.jdbcUrl() == null ? "" : d.jdbcUrl().toLowerCase(java.util.Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:") && !url.contains("readonlymode=")) {
            cfg.addDataSourceProperty("readOnlyMode", "always");
        }
    }

    @PreDestroy
    void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
