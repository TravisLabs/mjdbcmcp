-- Application schema. Applied on every boot by spring.sql.init, so every statement is idempotent.
-- SQLite has no dedicated migration tooling under a permissive licence (Flyway's SQLite module is
-- Redgate non-commercial), so schema changes go here as further IF NOT EXISTS statements.

-- Pre-1.0 rename: the connection/Datasource distinction in CONTEXT.md is binding, and this table
-- carried the wrong word. Nothing is deployed, so the old table is dropped rather than migrated.
DROP TABLE IF EXISTS jdbc_connection;

CREATE TABLE IF NOT EXISTS datasource (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    name                  TEXT    NOT NULL UNIQUE,
    description           TEXT,
    jdbc_url              TEXT    NOT NULL,
    driver_class          TEXT,
    username              TEXT,
    password_enc          TEXT,
    -- Comma-separated Capability names: select, dml, ddl_create, ddl_alter, ddl_drop.
    -- Empty means the Datasource can do nothing and is effectively parked.
    capabilities          TEXT    NOT NULL DEFAULT 'select',
    -- Object Allowlist entries, newline-separated. Empty means unrestricted.
    object_allowlist      TEXT,
    -- Comma-separated tool names explicitly disabled for this Datasource.
    disabled_tools        TEXT,
    enabled               INTEGER NOT NULL DEFAULT 1,
    default_schema        TEXT,
    max_rows              INTEGER NOT NULL DEFAULT 1000,
    max_cell_chars        INTEGER NOT NULL DEFAULT 4096,
    query_timeout_seconds INTEGER NOT NULL DEFAULT 30,
    max_pool_size         INTEGER NOT NULL DEFAULT 5,
    min_idle              INTEGER NOT NULL DEFAULT 0,
    connection_timeout_ms INTEGER NOT NULL DEFAULT 30000,
    idle_timeout_ms       INTEGER NOT NULL DEFAULT 600000,
    max_lifetime_ms       INTEGER NOT NULL DEFAULT 1800000,
    validation_query      TEXT,
    created_at            TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_datasource_enabled ON datasource (enabled);

-- One row per MCP tool call, including the ones that were refused — a refusal rate is the signal
-- that an agent is fighting the configuration, which is exactly what an operator wants to see.
-- Bound parameter values are deliberately not stored; see ADR-0012.
CREATE TABLE IF NOT EXISTS query_log (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    datasource       TEXT,
    tool             TEXT    NOT NULL,
    sql_text         TEXT,
    started_at       TEXT    NOT NULL,
    started_epoch_ms INTEGER NOT NULL,
    duration_ms      INTEGER NOT NULL,
    outcome          TEXT    NOT NULL,   -- ok | refused | failed | cancelled
    refusal_kind     TEXT,
    error            TEXT,
    row_count        INTEGER,
    row_cap_reached  INTEGER,
    update_count     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_query_log_started ON query_log (started_epoch_ms);
CREATE INDEX IF NOT EXISTS idx_query_log_datasource ON query_log (datasource, started_epoch_ms);
CREATE INDEX IF NOT EXISTS idx_query_log_duration ON query_log (duration_ms);
