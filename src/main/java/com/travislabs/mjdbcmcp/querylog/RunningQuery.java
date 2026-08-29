package com.travislabs.mjdbcmcp.querylog;

import java.time.Instant;

/**
 * A tool call currently executing.
 *
 * <p>Kept in memory only; vanished if the process dies, because a query that was running when the
 * server crashed is not running now.
 */
public record RunningQuery(
        long id,
        String datasource,
        String tool,
        String sql,
        Instant startedAt,
        long elapsedMs) {
}
