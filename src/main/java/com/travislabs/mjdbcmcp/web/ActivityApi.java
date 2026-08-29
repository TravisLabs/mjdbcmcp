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

    private final QueryLogService queryLog;
    private final DatasourceService datasources;

    public ActivityApi(QueryLogService queryLog, DatasourceService datasources) {
        this.queryLog = queryLog;
        this.datasources = datasources;
    }

    /**
     * Calls in flight right now, plus live pool state. Polled at a second or two, so it stays
     * in-memory work only — no query log read on this path.
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

    @GetMapping("/recent")
    public List<QueryExecution> recent(@RequestParam(defaultValue = "60") int windowMinutes,
                                       @RequestParam(defaultValue = "50") int limit) {
        return queryLog.recent(Duration.ofMinutes(clamp(windowMinutes, 1, 60 * 24 * 30)), clamp(limit, 1, 500));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
