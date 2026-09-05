package com.travislabs.mjdbcmcp.querylog;

import java.time.Instant;

/**
 * A tool call currently executing.
 *
 * <p>Kept in memory only; vanished if the process dies, because a query that was running when the
 * server crashed is not running now.
 *
 * @param id         unique in-memory execution ID
 * @param datasource target Datasource name
 * @param tool       tool name being executed
 * @param sql        sanitized/truncated statement text, or null
 * @param startedAt  timestamp when the tool call began
 * @param elapsedMs  milliseconds elapsed since the call began
 */
public record RunningQuery(
        long id,
        String datasource,
        String tool,
        String sql,
        Instant startedAt,
        long elapsedMs) {
}
