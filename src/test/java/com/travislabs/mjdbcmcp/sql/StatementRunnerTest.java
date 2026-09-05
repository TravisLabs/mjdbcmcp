package com.travislabs.mjdbcmcp.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.ObjectAllowlist;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatementRunner} verifying query execution, result encoding, script execution,
 * and parameter binding against an in-memory SQLite database.
 */
class StatementRunnerTest {

    /** Live JDBC connection to in-memory database. */
    private Connection connection;
    /** SQL classifier instance. */
    private SqlClassifier classifier;
    /** Statement runner under test. */
    private StatementRunner runner;
    /** Test Datasource configuration. */
    private Datasource datasource;

    /** Sets up in-memory SQLite schema and test fixtures before each test. */
    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE items (id INT PRIMARY KEY, name TEXT, val INT)");
            st.execute("INSERT INTO items VALUES (1, 'item1', 10), (2, 'item2', 20), (3, 'item3', 30)");
        }
        classifier = new SqlClassifier();
        runner = new StatementRunner(new ResultEncoder(), classifier);
        datasource = new Datasource(1L, "mem", null, "jdbc:sqlite::memory:", null, null, null,
                Set.of(Capability.SELECT, Capability.DML, Capability.DDL_CREATE, Capability.DDL_DROP),
                ObjectAllowlist.unrestricted(), Set.of(), true, null,
                2, 4096, 30, 2, 0, 30_000L, 600_000L, 1_800_000L, null);
    }

    /** Closes the test database connection after each test. */
    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /** Verifies that queries hitting the row cap report rowCapReached and include truncation message. */
    @Test
    void queryReachesCapAndIncludesMessage() throws Exception {
        Map<String, Object> result = runner.query(connection, datasource, "SELECT * FROM items", null, 2);

        assertThat(result.get("rowCount")).isEqualTo(2);
        assertThat(result.get("rowCapReached")).isEqualTo(true);
        assertThat(result.get("message")).isEqualTo("Result set limit reached (capped at 2 rows).");
    }

    /** Verifies that queries returning fewer rows than the cap do not include truncation message. */
    @Test
    void queryUnderCapDoesNotIncludeCapMessage() throws Exception {
        Map<String, Object> result = runner.query(connection, datasource, "SELECT * FROM items WHERE id = 1", null, 2);

        assertThat(result.get("rowCount")).isEqualTo(1);
        assertThat(result.get("rowCapReached")).isEqualTo(false);
        assertThat(result.get("message")).isNull();
    }

    /** Verifies atomic execution and update counts across multi-statement transaction scripts. */
    @Test
    void executeScriptRunsAtomicTransactions() throws Exception {
        String script = """
                INSERT INTO items VALUES (4, 'item4', 40);
                INSERT INTO items VALUES (5, 'item5', 50);
                UPDATE items SET val = 99 WHERE id = 4;
                """;
        ScriptClassification sc = classifier.classifyScript(script);
        Map<String, Object> result = runner.executeScript(connection, datasource, sc, null);

        assertThat(result.get("committed")).isEqualTo(true);
        assertThat(result.get("totalUpdateCount")).isEqualTo(3L);
        assertThat(result.get("statementsExecuted")).isEqualTo(3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stmts = (List<Map<String, Object>>) result.get("statements");
        assertThat(stmts).hasSize(3);
        assertThat(stmts.get(0).get("updateCount")).isEqualTo(1);
        assertThat(stmts.get(1).get("updateCount")).isEqualTo(1);
        assertThat(stmts.get(2).get("updateCount")).isEqualTo(1);
    }

    /** Verifies that a failure in any script statement rolls back earlier statements in the transaction. */
    @Test
    void executeScriptRollsBackEntirelyOnError() throws Exception {
        String script = """
                INSERT INTO items VALUES (10, 'item10', 100);
                INSERT INTO items VALUES (1, 'duplicate-id', 200);
                """;
        ScriptClassification sc = classifier.classifyScript(script);

        assertThatThrownBy(() -> runner.executeScript(connection, datasource, sc, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Statement 2 failed")
                .hasMessageContaining("Transaction rolled back; no statements were committed");

        // Verify that statement 1 (id 10) was rolled back
        Map<String, Object> check = runner.query(connection, datasource, "SELECT * FROM items WHERE id = 10", null, 10);
        assertThat(check.get("rowCount")).isEqualTo(0);
    }

    /** Verifies execution plan generation using SQLite dialect prefixing. */
    @Test
    void explainGeneratesQueryPlan() throws Exception {
        Map<String, Object> result = runner.explain(connection, datasource, "SELECT * FROM items WHERE id = 1", null, false);

        assertThat(result).containsKey("explainQuery");
        assertThat(result.get("explainQuery").toString()).startsWith("EXPLAIN QUERY PLAN");
        assertThat(result).containsKey("rows");
    }
}
