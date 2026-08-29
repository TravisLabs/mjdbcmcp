package com.travislabs.mjdbcmcp.sql;

import com.travislabs.mjdbcmcp.datasource.Datasource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.RollbackStatement;
import net.sf.jsqlparser.statement.SavepointStatement;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs queries, atomic write scripts, and query plan explanations on pooled Connections (ADR-0011,
 * ADR-0013, ADR-0014).
 */
@Component
public class StatementRunner {

    private static final Logger log = LoggerFactory.getLogger(StatementRunner.class);

    private final ResultEncoder encoder;
    private final SqlClassifier classifier;

    public StatementRunner(ResultEncoder encoder, SqlClassifier classifier) {
        this.encoder = encoder;
        this.classifier = classifier;
    }

    public Map<String, Object> query(Connection connection, Datasource d, String sql,
                                     List<Object> params, Integer requestedMaxRows) throws SQLException {
        int limit = requestedMaxRows == null || requestedMaxRows <= 0
                ? d.maxRows()
                : Math.min(requestedMaxRows, d.maxRows());
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(d.queryTimeoutSeconds());
            // One row past the cap, so "exactly at the limit" and "there was more" are distinguishable.
            ps.setMaxRows(limit + 1);
            // Stream rather than let the driver materialize a large table before we cap it.
            ps.setFetchSize(Math.min(limit + 1, 1000));
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return encoder.encode(rs, limit, d.maxCellChars());
            }
        }
    }

    /**
     * Executes an atomic multi-statement script inside an isolated transaction (ADR-0013).
     * Rolls back completely if any statement fails.
     */
    public Map<String, Object> executeScript(Connection connection, Datasource d,
                                            ScriptClassification script,
                                            List<Object> params) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        List<Map<String, Object>> statementResults = new ArrayList<>();
        long totalUpdateCount = 0;
        int statementIndex = 0;

        try {
            List<Statement> statements = script.parsedStatements();
            for (Statement stmt : statements) {
                statementIndex++;
                if (isTransactionControl(stmt)) {
                    Map<String, Object> txResult = new LinkedHashMap<>();
                    txResult.put("statementIndex", statementIndex);
                    txResult.put("verb", classifier.verbOf(stmt));
                    txResult.put("status", "ok");
                    statementResults.add(txResult);
                    continue;
                }

                String stmtSql = stmt.toString();
                try (PreparedStatement ps = connection.prepareStatement(stmtSql)) {
                    ps.setQueryTimeout(d.queryTimeoutSeconds());
                    ps.setMaxRows(d.maxRows() + 1);
                    // Bind parameters if there is only 1 statement in the script and params were provided
                    if (statements.size() == 1 && params != null && !params.isEmpty()) {
                        bind(ps, params);
                    }
                    boolean hasResultSet = ps.execute();
                    Map<String, Object> stmtResult = new LinkedHashMap<>();
                    stmtResult.put("statementIndex", statementIndex);
                    stmtResult.put("verb", classifier.verbOf(stmt));

                    if (hasResultSet) {
                        try (ResultSet rs = ps.getResultSet()) {
                            Map<String, Object> encoded = encoder.encode(rs, d.maxRows(), d.maxCellChars());
                            stmtResult.putAll(encoded);
                        }
                    }
                    int updateCount = ps.getUpdateCount();
                    if (updateCount >= 0) {
                        stmtResult.put("updateCount", updateCount);
                        totalUpdateCount += updateCount;
                    }
                    statementResults.add(stmtResult);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("Rollback failed for datasource '{}': {}", d.name(), rollbackEx.getMessage());
            }
            throw new SQLException("Statement " + statementIndex + " failed (" + e.getMessage()
                    + "). Transaction rolled back; no statements were committed.", e.getSQLState(), e.getErrorCode(), e);
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("committed", true);
        out.put("totalUpdateCount", totalUpdateCount);
        out.put("statementsExecuted", statementResults.size());
        out.put("statements", statementResults);
        // If single statement, hoist primary attributes for backward compatibility / ease of consumption
        if (statementResults.size() == 1) {
            Map<String, Object> single = statementResults.get(0);
            if (single.containsKey("updateCount")) {
                out.put("updateCount", single.get("updateCount"));
            }
            if (single.containsKey("rows")) {
                out.put("rows", single.get("rows"));
                out.put("columns", single.get("columns"));
                out.put("types", single.get("types"));
                out.put("rowCount", single.get("rowCount"));
                out.put("rowCapReached", single.get("rowCapReached"));
                if (single.containsKey("message")) {
                    out.put("message", single.get("message"));
                }
            }
        }
        return out;
    }

    /**
     * Explains a query plan with automated engine dialect prefixing (ADR-0014).
     */
    public Map<String, Object> explain(Connection connection, Datasource d, String sql,
                                       List<Object> params, Boolean analyze) throws SQLException {
        boolean runAnalyze = Boolean.TRUE.equals(analyze);
        DatabaseMetaData md = connection.getMetaData();
        String productName = md.getDatabaseProductName() == null ? "" : md.getDatabaseProductName().toLowerCase(Locale.ROOT);

        String explainSql;
        if (productName.contains("postgres")) {
            explainSql = runAnalyze
                    ? "EXPLAIN (FORMAT JSON, ANALYZE) " + sql
                    : "EXPLAIN (FORMAT JSON) " + sql;
        } else if (productName.contains("mysql") || productName.contains("mariadb")) {
            explainSql = runAnalyze
                    ? "EXPLAIN ANALYZE " + sql
                    : "EXPLAIN FORMAT=JSON " + sql;
        } else if (productName.contains("sqlite")) {
            explainSql = "EXPLAIN QUERY PLAN " + sql;
        } else {
            explainSql = "EXPLAIN " + sql;
        }

        try (PreparedStatement ps = connection.prepareStatement(explainSql)) {
            ps.setQueryTimeout(d.queryTimeoutSeconds());
            ps.setMaxRows(d.maxRows() + 1);
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Object> encoded = encoder.encode(rs, d.maxRows(), d.maxCellChars());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("databaseProduct", md.getDatabaseProductName());
                out.put("explainQuery", explainSql);
                out.put("analyzed", runAnalyze);
                out.putAll(encoded);
                return out;
            }
        }
    }

    private static boolean isTransactionControl(Statement statement) {
        if (statement instanceof Commit || statement instanceof RollbackStatement
                || statement instanceof SavepointStatement || statement instanceof SetStatement) {
            return true;
        }
        String str = statement.toString().trim().toUpperCase(Locale.ROOT);
        return str.startsWith("BEGIN") || str.startsWith("START TRANSACTION")
                || str.startsWith("COMMIT") || str.startsWith("ROLLBACK")
                || str.startsWith("SAVEPOINT");
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }
}
