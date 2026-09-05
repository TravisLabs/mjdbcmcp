package com.travislabs.mjdbcmcp.datasource;

import com.travislabs.mjdbcmcp.crypto.SecretCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** CRUD over {@code datasource} in the application SQLite database. */
@Repository
public class DatasourceRepository {

    /** Comma-separated list of database columns in the datasource table. */
    private static final String COLUMNS = """
            id, name, description, jdbc_url, driver_class, username, password_enc, capabilities,
            object_allowlist, disabled_tools, enabled, default_schema, max_rows, max_cell_chars,
            query_timeout_seconds, max_pool_size, min_idle, connection_timeout_ms, idle_timeout_ms,
            max_lifetime_ms, validation_query
            """;

    /** Spring JDBC client for database access. */
    private final JdbcClient jdbc;
    /** Cipher for encrypting and decrypting stored passwords. */
    private final SecretCipher cipher;

    /**
     * Constructs a DatasourceRepository with the given JDBC client and secret cipher.
     *
     * @param jdbc   JDBC client
     * @param cipher cipher for password encryption
     */
    public DatasourceRepository(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * Retrieves all configured Datasources ordered by name.
     *
     * @return list of all Datasource records
     */
    public List<Datasource> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM datasource ORDER BY name")
                .query(this::map)
                .list();
    }

    /**
     * Finds a Datasource by its unique name.
     *
     * @param name datasource name
     * @return optional containing the Datasource if found
     */
    public Optional<Datasource> findByName(String name) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM datasource WHERE name = ?")
                .param(name)
                .query(this::map)
                .optional();
    }

    /**
     * Finds a Datasource by its primary key ID.
     *
     * @param id datasource primary key
     * @return optional containing the Datasource if found
     */
    public Optional<Datasource> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM datasource WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    /**
     * Inserts a new Datasource into the SQLite database.
     *
     * @param d Datasource to insert
     * @return persisted Datasource with generated ID
     */
    public Datasource insert(Datasource d) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO datasource
                    (name, description, jdbc_url, driver_class, username, password_enc, capabilities,
                     object_allowlist, disabled_tools, enabled, default_schema, max_rows,
                     max_cell_chars, query_timeout_seconds, max_pool_size, min_idle,
                     connection_timeout_ms, idle_timeout_ms, max_lifetime_ms, validation_query)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .params(writeParams(d))
                .update(keys);
        return d.withId(keys.getKey() == null ? null : keys.getKey().longValue());
    }

    /**
     * Updates an existing Datasource record.
     *
     * @param id primary key ID
     * @param d  updated Datasource entity
     */
    public void update(long id, Datasource d) {
        List<Object> params = new ArrayList<>(writeParams(d));
        params.add(id);
        jdbc.sql("""
                UPDATE datasource SET
                    name = ?, description = ?, jdbc_url = ?, driver_class = ?, username = ?,
                    password_enc = ?, capabilities = ?, object_allowlist = ?, disabled_tools = ?,
                    enabled = ?, default_schema = ?, max_rows = ?, max_cell_chars = ?,
                    query_timeout_seconds = ?, max_pool_size = ?, min_idle = ?,
                    connection_timeout_ms = ?, idle_timeout_ms = ?, max_lifetime_ms = ?,
                    validation_query = ?, updated_at = datetime('now')
                WHERE id = ?
                """)
                .params(params)
                .update();
    }

    /**
     * Deletes a Datasource record by ID.
     *
     * @param id primary key ID to delete
     */
    public void delete(long id) {
        jdbc.sql("DELETE FROM datasource WHERE id = ?").param(id).update();
    }

    /**
     * Converts a Datasource record into positional parameters for insert or update SQL.
     *
     * @param d Datasource instance
     * @return list of parameter values
     */
    private List<Object> writeParams(Datasource d) {
        return Arrays.asList(
                d.name(), d.description(), d.jdbcUrl(), d.driverClass(), d.username(),
                cipher.encrypt(d.password()), Capability.format(d.capabilities()),
                d.allowlist().format(), formatTools(d.disabledTools()), d.enabled() ? 1 : 0,
                d.defaultSchema(), d.maxRows(), d.maxCellChars(), d.queryTimeoutSeconds(),
                d.maxPoolSize(), d.minIdle(), d.connectionTimeoutMs(),
                d.idleTimeoutMs(), d.maxLifetimeMs(), d.validationQuery());
    }

    /**
     * Maps a database row to a {@link Datasource} instance.
     *
     * @param rs     result set
     * @param rowNum row number
     * @return mapped Datasource instance
     * @throws SQLException if a database error occurs
     */
    private Datasource map(ResultSet rs, int rowNum) throws SQLException {
        return new Datasource(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("jdbc_url"),
                rs.getString("driver_class"),
                rs.getString("username"),
                cipher.decrypt(rs.getString("password_enc")),
                Capability.parse(rs.getString("capabilities")),
                ObjectAllowlist.parse(rs.getString("object_allowlist")),
                parseTools(rs.getString("disabled_tools")),
                rs.getInt("enabled") != 0,
                rs.getString("default_schema"),
                rs.getInt("max_rows"),
                rs.getInt("max_cell_chars"),
                rs.getInt("query_timeout_seconds"),
                rs.getInt("max_pool_size"),
                rs.getInt("min_idle"),
                rs.getLong("connection_timeout_ms"),
                rs.getLong("idle_timeout_ms"),
                rs.getLong("max_lifetime_ms"),
                rs.getString("validation_query"));
    }

    /**
     * Formats a set of disabled tool names into a sorted comma-separated string.
     *
     * @param tools set of tool names
     * @return comma-separated string or null if empty
     */
    private static String formatTools(Set<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return String.join(",", tools.stream().sorted().toList());
    }

    /**
     * Parses a comma-separated string of disabled tool names into a lowercase set.
     *
     * @param raw comma-separated tool names
     * @return set of tool names
     */
    private static Set<String> parseTools(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
