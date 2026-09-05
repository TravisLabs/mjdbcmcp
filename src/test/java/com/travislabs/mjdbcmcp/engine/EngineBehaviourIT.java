package com.travislabs.mjdbcmcp.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.travislabs.mjdbcmcp.config.AppProperties;
import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.DriverRegistry;
import com.travislabs.mjdbcmcp.datasource.ObjectAllowlist;
import com.travislabs.mjdbcmcp.datasource.PoolRegistry;
import com.travislabs.mjdbcmcp.sql.MetadataReader;
import com.travislabs.mjdbcmcp.sql.ResultEncoder;
import com.travislabs.mjdbcmcp.sql.SqlClassifier;
import com.travislabs.mjdbcmcp.sql.StatementRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The second testing tier: what only a real engine can prove — read-only Connection enforcement,
 * {@code setMaxRows}, atomic multi-statement scripts, and {@code DatabaseMetaData} shape.
 *
 * <p>Skipped automatically when no container runtime is reachable. On this machine that means
 * Podman: see AGENTS.md for the {@code DOCKER_HOST} export.
 */
@Testcontainers
@EnabledIfDockerAvailable
class EngineBehaviourIT {

    /** PostgreSQL test container. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** MySQL test container. */
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    /** Temporary configuration directory. */
    @TempDir
    static Path configDir;

    /** Connection pool registry under test. */
    private static PoolRegistry pools;
    /** Statement runner under test. */
    private static StatementRunner runner;
    /** SQL classifier instance. */
    private static SqlClassifier classifier;
    /** Metadata reader under test. */
    private static MetadataReader metadata;

    /** Initializes shared components before all container tests. */
    @BeforeAll
    static void wire() {
        var props = new AppProperties(configDir,
                new AppProperties.Mcp("/mcp", "test", List.of(), List.of(), Duration.ofSeconds(30), List.of()),
                null);
        var drivers = new DriverRegistry(props);
        pools = new PoolRegistry(drivers);
        classifier = new SqlClassifier();
        runner = new StatementRunner(new ResultEncoder(), classifier);
        metadata = new MetadataReader();
    }

    /** Closes all open connection pools after all container tests finish. */
    @AfterAll
    static void closePools() {
        for (String name : List.of("pg-read", "pg-write", "mysql-read", "mysql-write", "pg-rows", "pg-script")) {
            pools.evict(name);
        }
    }

    // ── Read-only Connection enforcement ──────────────────────────────────────────────────────

    /** Verifies that a read-only PostgreSQL connection enforces read-only mode at the database engine level. */
    @Test
    void selectOnlyDatasourceIsRefusedAWriteByTheEngineItself() throws Exception {
        seedPostgres();
        Datasource readOnly = postgres("pg-read", Set.of(Capability.SELECT));

        try (Connection conn = pools.pool(readOnly).getConnection()) {
            assertThat(conn.isReadOnly()).isTrue();
            // The engine refuses, not SqlClassifier: this is the backstop under Classification.
            assertThatThrownBy(() -> runner.executeScript(conn, readOnly,
                    classifier.classifyScript("INSERT INTO widget VALUES (99, 'x')"), null))
                    .isInstanceOf(SQLException.class);
        }
    }

    /** Verifies that a read-only MySQL connection enforces read-only mode at the database engine level. */
    @Test
    void selectOnlyMysqlDatasourceIsAlsoRefusedAWriteByTheEngine() throws Exception {
        seedMysql();
        Datasource readOnly = new Datasource(1L, "mysql-read", null, MYSQL.getJdbcUrl(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), Set.of(Capability.SELECT),
                ObjectAllowlist.unrestricted(), Set.of(), true, null,
                1000, 4096, 30, 2, 0, 30_000L, 600_000L, 1_800_000L, null);

        try (Connection conn = pools.pool(readOnly).getConnection()) {
            assertThat(conn.isReadOnly()).isTrue();
            assertThatThrownBy(() -> runner.executeScript(conn, readOnly,
                    classifier.classifyScript("INSERT INTO widget VALUES (98, 'x')"), null))
                    .isInstanceOf(SQLException.class);
        }
    }

    /** Verifies that write-capable Datasources create writable JDBC connections. */
    @Test
    void writeCapableDatasourceIsNotReadOnly() throws Exception {
        seedPostgres();
        Datasource writable = postgres("pg-write", Set.of(Capability.SELECT, Capability.DML));

        try (Connection conn = pools.pool(writable).getConnection()) {
            assertThat(conn.isReadOnly()).isFalse();
            Map<String, Object> result = runner.executeScript(conn, writable,
                    classifier.classifyScript("INSERT INTO widget VALUES (50, 'inserted')"), null);
            assertThat(result.get("updateCount")).isEqualTo(1);
        }
    }

    // ── Atomic multi-statement script execution & rollback ─────────────────────────────────────

    /** Verifies atomic execution and commit of multi-statement scripts against PostgreSQL. */
    @Test
    void atomicScriptExecutesAndCommitsAllStatements() throws Exception {
        seedPostgres();
        Datasource d = postgres("pg-script", Set.of(Capability.SELECT, Capability.DML));

        String script = """
                INSERT INTO widget VALUES (101, 'one');
                INSERT INTO widget VALUES (102, 'two');
                UPDATE widget SET name = 'updated-one' WHERE id = 101;
                """;

        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> result = runner.executeScript(conn, d, classifier.classifyScript(script), null);
            assertThat(result.get("committed")).isEqualTo(true);
            assertThat(result.get("totalUpdateCount")).isEqualTo(3L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stmts = (List<Map<String, Object>>) result.get("statements");
            assertThat(stmts).hasSize(3);
        }

        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> queryRes = runner.query(conn, d, "SELECT * FROM widget WHERE id IN (101, 102)", null, 10);
            assertThat(queryRes.get("rowCount")).isEqualTo(2);
        }
    }

    /** Verifies that transaction errors roll back all prior statements in a multi-statement script against PostgreSQL. */
    @Test
    void atomicScriptRollsBackEntirelyIfAnyStatementFails() throws Exception {
        seedPostgres();
        Datasource d = postgres("pg-script", Set.of(Capability.SELECT, Capability.DML));

        // 1st insert succeeds, 2nd insert violates primary key (duplicate 200), so everything rolls back
        String script = """
                INSERT INTO widget VALUES (200, 'first');
                INSERT INTO widget VALUES (200, 'duplicate-pk');
                """;

        try (Connection conn = pools.pool(d).getConnection()) {
            assertThatThrownBy(() -> runner.executeScript(conn, d, classifier.classifyScript(script), null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Transaction rolled back; no statements were committed");
        }

        // Verify that 'first' (id 200) was rolled back and does not exist
        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> queryRes = runner.query(conn, d, "SELECT * FROM widget WHERE id = 200", null, 10);
            assertThat(queryRes.get("rowCount")).isEqualTo(0);
        }
    }

    // ── setMaxRows ────────────────────────────────────────────────────────────────────────────

    /** Verifies engine setMaxRows enforcement and result truncation indicators against PostgreSQL. */
    @Test
    void rowCapIsEnforcedAndReported() throws Exception {
        seedPostgres();
        Datasource d = postgres("pg-rows", Set.of(Capability.SELECT));

        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> capped = runner.query(conn, d, "SELECT * FROM many", null, 5);
            assertThat(capped.get("rowCount")).isEqualTo(5);
            // Silent truncation is how an agent reports a wrong row count to a user as fact.
            assertThat(capped.get("rowCapReached")).isEqualTo(true);
            assertThat(capped.get("message")).isEqualTo("Result set limit reached (capped at 5 rows).");

            Map<String, Object> complete = runner.query(conn, d, "SELECT * FROM many WHERE n <= 3", null, 5);
            assertThat(complete.get("rowCount")).isEqualTo(3);
            assertThat(complete.get("rowCapReached")).isEqualTo(false);
        }
    }

    /** Verifies accurate precision preservation for nulls and high-precision numeric values without lossy float conversions. */
    @Test
    void nullsAndBigNumericsSurviveEncoding() throws Exception {
        seedPostgres();
        Datasource d = postgres("pg-rows", Set.of(Capability.SELECT));

        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> result = runner.query(conn, d,
                    "SELECT NULL::text AS a, 1234567890123456789012.12345::numeric AS b", null, 10);

            @SuppressWarnings("unchecked")
            List<List<Object>> rows = (List<List<Object>>) result.get("rows");
            assertThat(rows.get(0).get(0)).isNull();
            // Beyond double precision, so it goes out as a string rather than silently rounded.
            assertThat(rows.get(0).get(1)).isInstanceOf(String.class);
            assertThat(rows.get(0).get(1).toString()).startsWith("1234567890123456789012.12345");
        }
    }

    // ── DatabaseMetaData shape, on both engines ───────────────────────────────────────────────

    /** Verifies table metadata reading and column introspection against PostgreSQL. */
    @Test
    void postgresMetadataDescribesTheTable() throws Exception {
        seedPostgres();
        Datasource d = postgres("pg-read", Set.of(Capability.SELECT));

        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> described = metadata.describeTable(conn, "public", "widget");
            assertThat(described.get("primaryKey")).isEqualTo(List.of("id"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) described.get("columns");
            assertThat(columns).extracting(c -> c.get("name")).contains("id", "name");
        }
    }

    /** Verifies table metadata reading and column introspection against MySQL. */
    @Test
    void mysqlMetadataDescribesTheTable() throws Exception {
        seedMysql();
        Datasource d = mysql("mysql-write");

        try (Connection conn = pools.pool(d).getConnection()) {
            Map<String, Object> info = metadata.databaseInfo(conn);
            assertThat(String.valueOf(info.get("product"))).containsIgnoringCase("mysql");

            // MySQL reports schemas as catalogs, which is exactly the kind of difference H2 would
            // have hidden; describe still has to work.
            Map<String, Object> described = metadata.describeTable(conn, null, "widget");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) described.get("columns");
            assertThat(columns).extracting(c -> c.get("name")).contains("id", "name");
        }
    }

    /** Verifies that the Object Allowlist hides excluded tables from metadata introspection queries. */
    @Test
    void allowlistHidesTablesFromIntrospection() throws Exception {
        seedPostgres();
        Datasource scoped = new Datasource(1L, "pg-read", null, POSTGRES.getJdbcUrl(), null,
                POSTGRES.getUsername(), POSTGRES.getPassword(), Set.of(Capability.SELECT),
                ObjectAllowlist.parse("public.widget"), Set.of(), true, "public",
                1000, 4096, 30, 2, 0, 30_000L, 600_000L, 1_800_000L, null);

        try (Connection conn = pools.pool(scoped).getConnection()) {
            List<Map<String, Object>> tables = metadata.tables(conn, scoped, "public", null, null);
            assertThat(tables).extracting(t -> t.get("name")).contains("widget").doesNotContain("many");
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private static void seedPostgres() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS widget (id INT PRIMARY KEY, name VARCHAR(50))");
            st.execute("CREATE TABLE IF NOT EXISTS many (n INT PRIMARY KEY)");
            st.execute("INSERT INTO many SELECT generate_series(1, 20) ON CONFLICT DO NOTHING");
        }
    }

    private static void seedMysql() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS widget (id INT PRIMARY KEY, name VARCHAR(50))");
        }
    }

    private static Datasource postgres(String name, Set<Capability> capabilities) {
        return new Datasource(1L, name, null, POSTGRES.getJdbcUrl(), null, POSTGRES.getUsername(),
                POSTGRES.getPassword(), capabilities, ObjectAllowlist.unrestricted(), Set.of(), true,
                "public", 1000, 4096, 30, 2, 0, 30_000L, 600_000L, 1_800_000L, null);
    }

    private static Datasource mysql(String name) {
        return new Datasource(1L, name, null, MYSQL.getJdbcUrl(), null, MYSQL.getUsername(),
                MYSQL.getPassword(), Set.of(Capability.SELECT, Capability.DML),
                ObjectAllowlist.unrestricted(), Set.of(), true, null,
                1000, 4096, 30, 2, 0, 30_000L, 600_000L, 1_800_000L, null);
    }
}
