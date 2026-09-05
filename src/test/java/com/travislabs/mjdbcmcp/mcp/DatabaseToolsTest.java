package com.travislabs.mjdbcmcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.travislabs.mjdbcmcp.config.AppProperties;
import com.travislabs.mjdbcmcp.crypto.SecretCipher;
import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.DatasourceRepository;
import com.travislabs.mjdbcmcp.datasource.DatasourceService;
import com.travislabs.mjdbcmcp.datasource.DriverRegistry;
import com.travislabs.mjdbcmcp.datasource.ObjectAllowlist;
import com.travislabs.mjdbcmcp.datasource.PoolRegistry;
import com.travislabs.mjdbcmcp.querylog.QueryLogRepository;
import com.travislabs.mjdbcmcp.querylog.QueryLogService;
import com.travislabs.mjdbcmcp.sql.MetadataReader;
import com.travislabs.mjdbcmcp.sql.ResultEncoder;
import com.travislabs.mjdbcmcp.sql.SqlClassifier;
import com.travislabs.mjdbcmcp.sql.StatementRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link DatabaseTools} verifying MCP tool registration, execution, error handling,
 * and refusal responses.
 */
class DatabaseToolsTest {

    /** Temporary directory for SQLite application and target databases. */
    @TempDir
    Path tempDir;

    /** Connection pool for application metadata and query log database. */
    private HikariDataSource appDataSource;
    /** Connection pool for target database. */
    private HikariDataSource targetDataSource;
    /** Datasource service managing configurations. */
    private DatasourceService datasourceService;
    /** DatabaseTools under test. */
    private DatabaseTools tools;

    /** Sets up application and target SQLite database schemas and service instances. */
    @BeforeEach
    void setUp() throws Exception {
        // App database for metadata and logs
        HikariConfig appCfg = new HikariConfig();
        appCfg.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("app.db"));
        appDataSource = new HikariDataSource(appCfg);
        JdbcClient appJdbc = JdbcClient.create(appDataSource);
        for (String st : schema().split(";")) {
            if (!st.isBlank()) {
                appJdbc.sql(st).update();
            }
        }

        // Target database
        Path targetDb = tempDir.resolve("target.db");
        HikariConfig targetCfg = new HikariConfig();
        targetCfg.setJdbcUrl("jdbc:sqlite:" + targetDb);
        targetDataSource = new HikariDataSource(targetCfg);
        try (Connection c = targetDataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE products (id INT PRIMARY KEY, title TEXT, price INT)");
            s.execute("INSERT INTO products VALUES (1, 'Book', 15), (2, 'Pen', 3)");
        }

        AppProperties props = new AppProperties(tempDir,
                new AppProperties.Mcp("/mcp", "test", List.of(), List.of(), Duration.ofSeconds(30), List.of()),
                new AppProperties.QueryLog(true, true, 4000, Duration.ofDays(7), 1000, 100));

        SecretCipher cipher = new SecretCipher(props);
        DatasourceRepository dsRepo = new DatasourceRepository(appJdbc, cipher);
        DriverRegistry driverRegistry = new DriverRegistry(props);
        PoolRegistry poolRegistry = new PoolRegistry(driverRegistry);
        datasourceService = new DatasourceService(dsRepo, poolRegistry);

        QueryLogRepository queryLogRepo = new QueryLogRepository(appJdbc);
        QueryLogService queryLogService = new QueryLogService(queryLogRepo, props);
        SqlClassifier classifier = new SqlClassifier();
        StatementRunner runner = new StatementRunner(new ResultEncoder(), classifier);
        MetadataReader metadata = new MetadataReader();
        JacksonMcpJsonMapper json = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        tools = new DatabaseTools(datasourceService, classifier, runner, metadata, queryLogService, json);

        Datasource d = new Datasource(null, "demo", "Demo DB", "jdbc:sqlite:" + targetDb, null,
                null, null, Set.of(Capability.SELECT, Capability.DML),
                ObjectAllowlist.unrestricted(), Set.of("database_info"), true, null,
                100, 4096, 30, 5, 0, 30_000L, 600_000L, 1_800_000L, null);
        datasourceService.create(d);
    }

    /** Closes application and target database pools after each test. */
    @AfterEach
    void tearDown() {
        appDataSource.close();
        targetDataSource.close();
    }

    /** Verifies that attempting to invoke an explicitly disabled tool returns a DISABLED_TOOL refusal. */
    @Test
    void disabledToolIsRefused() {
        var spec = tools.databaseInfo();
        CallToolResult res = spec.callHandler().apply(null,
                new CallToolRequest("database_info", Map.of("datasource", "demo")));

        assertThat(res.isError()).isTrue();
        String text = ((TextContent) res.content().get(0)).text();
        assertThat(text).startsWith("disabled_tool: Tool 'database_info' is disabled on datasource 'demo'.");
    }

    /** Verifies successful execution of read-only SELECT queries via raw_query. */
    @Test
    void rawQueryExecutesRead() {
        var spec = tools.rawQuery();
        CallToolResult res = spec.callHandler().apply(null,
                new CallToolRequest("raw_query", Map.of("datasource", "demo", "sql", "SELECT * FROM products")));

        assertThat(res.isError()).isFalse();
        String text = ((TextContent) res.content().get(0)).text();
        assertThat(text).contains("\"rowCount\":2").contains("Book").contains("Pen");
    }

    /** Verifies query execution plan generation via explain_query. */
    @Test
    void explainQueryGeneratesPlan() {
        var spec = tools.explainQuery();
        CallToolResult res = spec.callHandler().apply(null,
                new CallToolRequest("explain_query", Map.of("datasource", "demo", "sql", "SELECT * FROM products WHERE id = 1")));

        assertThat(res.isError()).isFalse();
        String text = ((TextContent) res.content().get(0)).text();
        assertThat(text).contains("explainQuery").contains("EXPLAIN QUERY PLAN");
    }

    /** Verifies atomic execution of multi-statement modification scripts via raw_execute. */
    @Test
    void rawExecuteRunsScript() {
        var spec = tools.rawExecute();
        String script = """
                INSERT INTO products VALUES (3, 'Notebook', 7);
                UPDATE products SET price = 8 WHERE id = 3;
                """;
        CallToolResult res = spec.callHandler().apply(null,
                new CallToolRequest("raw_execute", Map.of("datasource", "demo", "sql", script)));

        assertThat(res.isError()).isFalse();
        String text = ((TextContent) res.content().get(0)).text();
        assertThat(text).contains("\"committed\":true").contains("\"totalUpdateCount\":2");
    }

    /** Loads and strips comments from the SQLite schema definition script. */
    private String schema() throws IOException {
        String raw = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        return raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
