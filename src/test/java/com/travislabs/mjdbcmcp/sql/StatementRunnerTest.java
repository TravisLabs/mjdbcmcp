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

class StatementRunnerTest {

    private Connection connection;
    private SqlClassifier classifier;
    private StatementRunner runner;
    private Datasource datasource;

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

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void queryReachesCapAndIncludesMessage() throws Exception {
        Map<String, Object> result = runner.query(connection, datasource, "SELECT * FROM items", null, 2);

        assertThat(result.get("rowCount")).isEqualTo(2);
        assertThat(result.get("rowCapReached")).isEqualTo(true);
        assertThat(result.get("message")).isEqualTo("Result set limit reached (capped at 2 rows).");
    }

    @Test
    void queryUnderCapDoesNotIncludeCapMessage() throws Exception {
        Map<String, Object> result = runner.query(connection, datasource, "SELECT * FROM items WHERE id = 1", null, 2);

        assertThat(result.get("rowCount")).isEqualTo(1);
        assertThat(result.get("rowCapReached")).isEqualTo(false);
        assertThat(result.get("message")).isNull();
    }

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

    @Test
    void explainGeneratesQueryPlan() throws Exception {
        Map<String, Object> result = runner.explain(connection, datasource, "SELECT * FROM items WHERE id = 1", null, false);

        assertThat(result).containsKey("explainQuery");
        assertThat(result.get("explainQuery").toString()).startsWith("EXPLAIN QUERY PLAN");
        assertThat(result).containsKey("rows");
    }
}
