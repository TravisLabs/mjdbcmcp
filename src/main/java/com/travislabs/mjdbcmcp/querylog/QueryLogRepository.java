package com.travislabs.mjdbcmcp.querylog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code query_log}. Aggregation happens in SQL rather than by pulling durations
 * into Java: the table is retention-capped and indexed, and one source of truth for the numbers is
 * worth more than saving a few small queries.
 */
@Repository
public class QueryLogRepository {

    /** Comma-separated list of column names for the query_log table. */
    private static final String COLUMNS = """
            id, datasource, tool, sql_text, started_at, started_epoch_ms, duration_ms, outcome,
            refusal_kind, error, row_count, row_cap_reached, update_count
            """;

    /** Spring JDBC client for database operations. */
    private final JdbcClient jdbc;

    /**
     * Constructs a QueryLogRepository with the given JDBC client.
     *
     * @param jdbc Spring JDBC client
     */
    public QueryLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a batch of query execution records into the log.
     *
     * @param batch list of executions to insert
     */
    public void insert(List<QueryExecution> batch) {
        for (QueryExecution e : batch) {
            jdbc.sql("""
                    INSERT INTO query_log
                        (datasource, tool, sql_text, started_at, started_epoch_ms, duration_ms, outcome,
                         refusal_kind, error, row_count, row_cap_reached, update_count)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """)
                    .params(java.util.Arrays.asList(
                            e.datasource(), e.tool(), e.sql(), e.startedAt().toString(),
                            e.startedAt().toEpochMilli(), e.durationMs(), e.outcome().wireName(),
                            e.refusalKind(), e.error(), e.rowCount(),
                            e.rowCapReached() == null ? null : (e.rowCapReached() ? 1 : 0),
                            e.updateCount()))
                    .update();
        }
    }

    /** Drops records past their retention, then anything above the row cap, oldest first. */
    public int prune(Instant olderThan, int maxRows) {
        int removed = jdbc.sql("DELETE FROM query_log WHERE started_epoch_ms < ?")
                .param(olderThan.toEpochMilli())
                .update();
        removed += jdbc.sql("""
                DELETE FROM query_log WHERE id IN (
                    SELECT id FROM query_log ORDER BY started_epoch_ms DESC LIMIT -1 OFFSET ?
                )
                """)
                .param(maxRows)
                .update();
        return removed;
    }

    /**
     * Aggregates execution summary counters and averages over a filtered slice.
     *
     * @param whereClause WHERE SQL clause filter
     * @param params      parameters for the WHERE clause
     * @return base execution summary without percentiles
     */
    public QueryStats.Summary summary(String whereClause, List<Object> params) {
        QueryStats.Summary base = jdbc.sql("""
                SELECT COUNT(*) AS calls,
                       SUM(CASE WHEN outcome = 'ok' THEN 1 ELSE 0 END) AS ok,
                       SUM(CASE WHEN outcome = 'refused' THEN 1 ELSE 0 END) AS refused,
                       SUM(CASE WHEN outcome = 'failed' THEN 1 ELSE 0 END) AS failed,
                       SUM(CASE WHEN outcome = 'cancelled' THEN 1 ELSE 0 END) AS cancelled,
                       AVG(duration_ms) AS avg_ms,
                       MAX(duration_ms) AS max_ms,
                       SUM(COALESCE(row_count, 0)) AS rows_returned
                FROM query_log
                """ + whereClause)
                .params(params)
                .query((rs, n) -> new QueryStats.Summary(
                        rs.getLong("calls"), rs.getLong("ok"), rs.getLong("refused"), rs.getLong("failed"),
                        rs.getLong("cancelled"),
                        nullableLong(rs, "avg_ms"), null, null, nullableLong(rs, "max_ms"),
                        rs.getLong("rows_returned")))
                .single();
        return base;
    }

    /** Adds p50 and p95 to a summary. Separate because the tool breakdown does not need them. */
    public QueryStats.Summary withPercentiles(QueryStats.Summary summary, String whereClause, List<Object> params) {
        if (summary.calls() == 0) {
            return summary;
        }
        return new QueryStats.Summary(summary.calls(), summary.ok(), summary.refused(), summary.failed(),
                summary.cancelled(), summary.avgMs(), percentile(whereClause, params, summary.calls(), 50),
                percentile(whereClause, params, summary.calls(), 95), summary.maxMs(), summary.rowsReturned());
    }

    /**
     * SQLite has no percentile function, so this takes the value at the rank directly. Cheap: the
     * duration index makes it an ordered scan to a known offset.
     */
    private Long percentile(String whereClause, List<Object> params, long count, int percent) {
        long offset = Math.max(0, (count * percent / 100) - (percent == 100 ? 1 : 0));
        offset = Math.min(offset, count - 1);
        List<Object> withOffset = new ArrayList<>(params);
        withOffset.add(offset);
        return jdbc.sql("SELECT duration_ms FROM query_log " + whereClause
                + " ORDER BY duration_ms ASC LIMIT 1 OFFSET ?")
                .params(withOffset)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /**
     * Queries distinct non-null values for a column recorded since a given timestamp.
     *
     * @param column column name
     * @param since  start timestamp
     * @return list of distinct string values
     */
    public List<String> distinct(String column, Instant since) {
        return jdbc.sql("SELECT DISTINCT " + column + " AS v FROM query_log "
                + "WHERE started_epoch_ms >= ? AND " + column + " IS NOT NULL ORDER BY v")
                .param(since.toEpochMilli())
                .query(String.class)
                .list();
    }

    /**
     * Aggregates refusal counts grouped by refusal kind since a given timestamp.
     *
     * @param since start timestamp
     * @return list of refusal counts per kind
     */
    public List<QueryStats.RefusalCount> refusalCounts(Instant since) {
        return jdbc.sql("""
                SELECT refusal_kind AS kind, COUNT(*) AS n FROM query_log
                WHERE started_epoch_ms >= ? AND outcome = 'refused' AND refusal_kind IS NOT NULL
                GROUP BY refusal_kind ORDER BY n DESC
                """)
                .param(since.toEpochMilli())
                .query((rs, n) -> new QueryStats.RefusalCount(rs.getString("kind"), rs.getLong("n")))
                .list();
    }

    /**
     * Retrieves the slowest query executions since a given timestamp.
     *
     * @param since start timestamp
     * @param limit maximum number of records to return
     * @return list of slowest query executions
     */
    public List<QueryExecution> slowest(Instant since, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM query_log WHERE started_epoch_ms >= ? "
                + "ORDER BY duration_ms DESC LIMIT ?")
                .params(List.of(since.toEpochMilli(), limit))
                .query(this::map)
                .list();
    }

    /**
     * Retrieves recent query executions since a given timestamp.
     *
     * @param since start timestamp
     * @param limit maximum number of records to return
     * @return list of recent query executions
     */
    public List<QueryExecution> recent(Instant since, int limit) {
        return recent(since, limit, 0);
    }

    /**
     * Retrieves paginated recent query executions since a given timestamp.
     *
     * @param since  start timestamp
     * @param limit  maximum number of records to return
     * @param offset starting record offset
     * @return list of recent query executions
     */
    public List<QueryExecution> recent(Instant since, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM query_log WHERE started_epoch_ms >= ? "
                + "ORDER BY started_epoch_ms DESC LIMIT ? OFFSET ?")
                .params(List.of(since.toEpochMilli(), limit, offset))
                .query(this::map)
                .list();
    }

    /**
     * Returns the timestamp of the oldest record within or after the specified timestamp.
     *
     * @param since cutoff timestamp
     * @return optional containing the earliest record timestamp if any
     */
    public Optional<Instant> earliest(Instant since) {
        return jdbc.sql("SELECT MIN(started_epoch_ms) FROM query_log WHERE started_epoch_ms >= ?")
                .param(since.toEpochMilli())
                .query(Long.class)
                .optional()
                .map(Instant::ofEpochMilli);
    }

    /**
     * Counts the total number of records currently in the query log.
     *
     * @return total record count
     */
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM query_log").query(Long.class).single();
    }

    private QueryExecution map(ResultSet rs, int rowNum) throws SQLException {
        Integer rowCount = nullableInt(rs, "row_count");
        Integer updateCount = nullableInt(rs, "update_count");
        Integer capReached = nullableInt(rs, "row_cap_reached");
        return new QueryExecution(
                rs.getLong("id"),
                rs.getString("datasource"),
                rs.getString("tool"),
                rs.getString("sql_text"),
                Instant.ofEpochMilli(rs.getLong("started_epoch_ms")),
                rs.getLong("duration_ms"),
                QueryExecution.Outcome.parse(rs.getString("outcome")),
                rs.getString("refusal_kind"),
                rs.getString("error"),
                rowCount,
                capReached == null ? null : capReached != 0,
                updateCount);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : Math.round(value);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
