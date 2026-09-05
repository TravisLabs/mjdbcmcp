package com.travislabs.mjdbcmcp.web;

import com.travislabs.mjdbcmcp.datasource.DatasourceService;
import com.travislabs.mjdbcmcp.querylog.QueryExecution;
import com.travislabs.mjdbcmcp.querylog.QueryLogService;
import com.travislabs.mjdbcmcp.querylog.QueryStats;
import com.travislabs.mjdbcmcp.querylog.RunningQuery;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the server is doing and has done. Split from {@link AdminApi} because the dashboard polls
 * these and nothing here writes anything.
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityApi {

    /** Query log service for activity metrics and recent queries. */
    private final QueryLogService queryLog;
    /** Datasource service for accessing connection pool statistics. */
    private final DatasourceService datasources;

    /**
     * Constructs ActivityApi with query log and datasource services.
     *
     * @param queryLog    query log service
     * @param datasources datasource service
     */
    public ActivityApi(QueryLogService queryLog, DatasourceService datasources) {
        this.queryLog = queryLog;
        this.datasources = datasources;
    }

    /**
     * Calls in flight right now, plus live pool state. Polled at a second or two, so it stays
     * in-memory work only — no query log read on this path.
     *
     * @return map of running queries and live pool statistics
     */
    @GetMapping("/running")
    public Map<String, Object> running() {
        List<RunningQuery> running = queryLog.running();
        return Map.of(
                "running", running,
                "runningCount", running.size(),
                "pools", datasources.poolStats().stream().map(p -> Map.of(
                        "datasource", p.datasource(),
                        "total", p.total(),
                        "active", p.active(),
                        "idle", p.idle(),
                        "awaiting", p.awaiting(),
                        "max", p.max())).toList());
    }

    /**
     * Returns aggregate activity statistics and slowest queries over the requested time window.
     *
     * @param windowMinutes time window in minutes
     * @param slowest       maximum number of slowest queries to include
     * @return map containing stats and window metadata
     */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "60") int windowMinutes,
                                     @RequestParam(defaultValue = "10") int slowest) {
        QueryStats stats = queryLog.stats(Duration.ofMinutes(clamp(windowMinutes, 1, 60 * 24 * 30)),
                clamp(slowest, 1, 50));
        return Map.of(
                "enabled", queryLog.enabled(),
                "windowMinutes", windowMinutes,
                "stats", stats);
    }

    /**
     * Returns recent query executions with pagination support.
     *
     * @param windowMinutes time window in minutes
     * @param limit         maximum records to return
     * @param offset        starting record offset
     * @return list of recent query executions
     */
    @GetMapping("/recent")
    public List<QueryExecution> recent(@RequestParam(defaultValue = "60") int windowMinutes,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return queryLog.recent(Duration.ofMinutes(clamp(windowMinutes, 1, 60 * 24 * 30)),
                clamp(limit, 1, 500), Math.max(0, offset));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
