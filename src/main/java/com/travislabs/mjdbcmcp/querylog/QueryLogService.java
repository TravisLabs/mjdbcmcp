package com.travislabs.mjdbcmcp.querylog;

import com.travislabs.mjdbcmcp.Refusal;
import com.travislabs.mjdbcmcp.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Records what the tools did, and tracks what they are doing right now.
 *
 * <p>Two halves with different lifetimes. In-flight calls live in a map and are gone when the call
 * ends — a running query is not a fact that survives a restart. Finished calls go onto a bounded
 * queue and are written by one background thread, so the log never puts itself on the path of a
 * query. When the queue is full, records are dropped and counted; making an agent's query wait for
 * the audit trail would be the wrong trade.
 */
@Service
public class QueryLogService {

    private static final Logger log = LoggerFactory.getLogger(QueryLogService.class);
    private static final int WRITE_BATCH = 100;

    /** Repository for persisting and querying execution records. */
    private final QueryLogRepository repository;
    /** Query log configuration settings. */
    private final AppProperties.QueryLog config;

    /** Map of currently in-flight tool calls keyed by running execution ID. */
    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    /** Atomic sequence generator for execution IDs. */
    private final AtomicLong ids = new AtomicLong();
    /** Counter of query log records dropped due to a full queue. */
    private final AtomicLong dropped = new AtomicLong();
    /** Bounded queue of completed query execution records awaiting the background writer. */
    private final BlockingQueue<QueryExecution> pending;
    /** Dedicated background daemon thread that drains the execution queue. */
    private final Thread writer;
    /** Flag indicating that the service is shutting down. */
    private volatile boolean stopping;

    // Explicit: with a second constructor present, Spring has no single candidate to infer.
    /**
     * Constructs a QueryLogService using application properties.
     *
     * @param repository query log repository
     * @param props      application configuration properties
     */
    @org.springframework.beans.factory.annotation.Autowired
    public QueryLogService(QueryLogRepository repository, AppProperties props) {
        this(repository, props.queryLog());
    }

    /**
     * Direct-configuration constructor, so the behaviour can be tested without a whole context.
     * Package-private deliberately: two public constructors would leave Spring with no single
     * autowire candidate.
     */
    QueryLogService(QueryLogRepository repository, AppProperties.QueryLog config) {
        this.repository = repository;
        this.config = config;
        this.pending = new ArrayBlockingQueue<>(config.queueCapacity());
        this.writer = new Thread(this::drainForever, "query-log-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * Internal state for an in-flight tool call tracked in memory.
     */
    private record Running(
            long id,
            String datasource,
            String tool,
            String sql,
            Instant startedAt,
            String sessionId,
            Thread executionThread,
            AtomicBoolean cancelled) {
    }

    /**
     * Registers a call as in flight and returns the handle that finishes it. Always finish the
     * handle — a leaked one leaves a query on the dashboard forever.
     */
    public Handle begin(String datasource, String tool, String sql) {
        return begin(datasource, tool, sql, null);
    }

    /**
     * Registers a call as in flight with an associated MCP session ID for cancellation support.
     */
    public Handle begin(String datasource, String tool, String sql, String sessionId) {
        long id = ids.incrementAndGet();
        String storedSql = config.storeSql() ? truncate(sql, config.maxSqlChars()) : null;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(id, new Running(id, datasource, tool, storedSql, Instant.now(), sessionId, Thread.currentThread(), cancelled));
        return new Handle(id, cancelled);
    }

    /**
     * Signals cancellation for all in-flight queries associated with the given session ID.
     */
    public void cancelSession(String sessionId, Object requestId, String reason) {
        if (sessionId == null) {
            return;
        }
        for (Running r : running.values()) {
            if (sessionId.equals(r.sessionId())) {
                r.cancelled().set(true);
                if (r.executionThread() != null && r.executionThread().isAlive()) {
                    log.info("Interrupting execution thread for cancelled query {} on session {} (request={}, reason={})",
                            r.id(), sessionId, requestId, reason);
                    r.executionThread().interrupt();
                }
            }
        }
    }

    /** Calls in flight right now, longest-running first — the ones an operator is looking for. */
    public List<RunningQuery> running() {
        Instant now = Instant.now();
        List<RunningQuery> out = new ArrayList<>(running.size());
        for (Running r : running.values()) {
            out.add(new RunningQuery(r.id(), r.datasource(), r.tool(), r.sql(), r.startedAt(),
                    Math.max(0, now.toEpochMilli() - r.startedAt().toEpochMilli())));
        }
        out.sort((a, b) -> Long.compare(b.elapsedMs(), a.elapsedMs()));
        return out;
    }

    /**
     * Aggregates activity metrics and statistics over the specified time window.
     *
     * @param window       time window Duration
     * @param slowestLimit maximum number of slowest queries to include
     * @return calculated {@link QueryStats}
     */
    public QueryStats stats(java.time.Duration window, int slowestLimit) {
        Instant since = Instant.now().minus(window);
        String where = " WHERE started_epoch_ms >= ?";
        List<Object> params = List.of(since.toEpochMilli());

        QueryStats.Summary overall = repository.withPercentiles(
                repository.summary(where, params), where, params);

        List<QueryStats.Group> byDatasource = new ArrayList<>();
        for (String name : repository.distinct("datasource", since)) {
            String scoped = where + " AND datasource = ?";
            List<Object> scopedParams = List.of(since.toEpochMilli(), name);
            byDatasource.add(new QueryStats.Group(name, repository.withPercentiles(
                    repository.summary(scoped, scopedParams), scoped, scopedParams)));
        }

        List<QueryStats.Group> byTool = new ArrayList<>();
        for (String name : repository.distinct("tool", since)) {
            // No percentiles per tool: the breakdown is for spotting which tool is busy or failing,
            // and a p95 over a handful of calls is noise dressed as a number.
            byTool.add(new QueryStats.Group(name,
                    repository.summary(where + " AND tool = ?", List.of(since.toEpochMilli(), name))));
        }

        return new QueryStats(
                overall,
                byDatasource,
                byTool,
                repository.refusalCounts(since),
                repository.slowest(since, slowestLimit),
                repository.earliest(since).map(Instant::toString).orElse(null),
                dropped.get());
    }

    /**
     * Retrieves recent query executions within the specified time window.
     *
     * @param window time window Duration
     * @param limit  maximum number of records to return
     * @return list of recent query execution records
     */
    public List<QueryExecution> recent(java.time.Duration window, int limit) {
        return recent(window, limit, 0);
    }

    /**
     * Retrieves paginated recent query executions within the specified time window.
     *
     * @param window time window Duration
     * @param limit  maximum number of records to return
     * @param offset starting record offset
     * @return list of recent query execution records
     */
    public List<QueryExecution> recent(java.time.Duration window, int limit, int offset) {
        return repository.recent(Instant.now().minus(window), limit, offset);
    }

    /** Retention runs on a schedule rather than per write, so a burst is not also a delete storm. */
    @Scheduled(fixedDelayString = "${mjdbcmcp.query-log.prune-interval-ms:300000}", initialDelay = 60_000)
    void prune() {
        if (!config.enabled()) {
            return;
        }
        try {
            int removed = repository.prune(Instant.now().minus(config.retention()), config.maxRows());
            if (removed > 0) {
                log.debug("Pruned {} query log records", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Query log prune failed: {}", e.getMessage());
        }
    }

    /** The handle a caller closes to finish a record. */
    public final class Handle {

        /** In-flight execution ID. */
        private final long id;
        /** Cancellation flag shared with session-level cancellation handlers. */
        private final AtomicBoolean cancelled;

        private Handle(long id, AtomicBoolean cancelled) {
            this.id = id;
            this.cancelled = cancelled;
        }

        /**
         * Marks the query execution as successfully completed with the given reply payload.
         *
         * @param payload tool execution reply payload
         */
        public void ok(Map<String, Object> payload) {
            finish(QueryExecution.Outcome.OK, null, null, payload);
        }

        /**
         * Marks the query execution as refused due to a configuration or policy violation.
         *
         * @param refusal the refusal exception
         */
        public void refused(Refusal refusal) {
            finish(QueryExecution.Outcome.REFUSED, refusal.kind().name().toLowerCase(java.util.Locale.ROOT),
                    refusal.getMessage(), null);
        }

        /**
         * Marks the query execution as cancelled.
         *
         * @param reason cancellation reason
         */
        public void cancelled(String reason) {
            finish(QueryExecution.Outcome.CANCELLED, null, reason, null);
        }

        /**
         * Marks the query execution as failed with the given exception or error.
         *
         * @param error the failure cause
         */
        public void failed(Throwable error) {
            if ((cancelled != null && cancelled.get()) || isCancellation(error)) {
                finish(QueryExecution.Outcome.CANCELLED, null, errorMessage(error), null);
            } else {
                finish(QueryExecution.Outcome.FAILED, null, errorMessage(error), null);
            }
        }

        private void finish(QueryExecution.Outcome outcome, String refusalKind, String error,
                            Map<String, Object> payload) {
            Running started = running.remove(id);
            if (started == null) {
                return;
            }
            if (!config.enabled()) {
                return;
            }
            long durationMs = Math.max(0, Instant.now().toEpochMilli() - started.startedAt().toEpochMilli());
            QueryExecution record = new QueryExecution(null, started.datasource(), started.tool(),
                    started.sql(), started.startedAt(), durationMs, outcome, refusalKind, error,
                    intFrom(payload, "rowCount"), boolFrom(payload, "rowCapReached"),
                    intFrom(payload, "updateCount"));
            if (!pending.offer(record)) {
                dropped.incrementAndGet();
            }
        }
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "Operation cancelled";
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    public static boolean isCancellation(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable curr = error;
        while (curr != null) {
            if (curr instanceof InterruptedException
                    || curr instanceof java.io.InterruptedIOException
                    || curr instanceof java.nio.channels.ClosedByInterruptException
                    || curr instanceof CancellationException) {
                return true;
            }
            if (curr instanceof java.sql.SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if ("57014".equals(sqlState)) { // Postgres query_canceled
                    return true;
                }
                String msg = sqlEx.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase(Locale.ROOT);
                    if (lower.contains("canceling statement due to user request")
                            || lower.contains("statement canceled")
                            || lower.contains("statement cancelled")
                            || lower.contains("query was cancelled")
                            || lower.contains("query was canceled")
                            || lower.contains("operation was aborted")
                            || lower.contains("operation aborted")
                            || lower.contains("operation cancelled")
                            || lower.contains("operation canceled")) {
                        return true;
                    }
                }
            }
            String topMsg = curr.getMessage();
            if (topMsg != null) {
                String lower = topMsg.toLowerCase(Locale.ROOT);
                if (lower.contains("aborterror") || lower.contains("operation was aborted")
                        || lower.contains("operation aborted") || lower.contains("operation canceled")
                        || lower.contains("operation cancelled")) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    private void drainForever() {
        List<QueryExecution> batch = new ArrayList<>(WRITE_BATCH);
        while (!stopping || !pending.isEmpty()) {
            try {
                QueryExecution first = pending.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                pending.drainTo(batch, WRITE_BATCH - 1);
                repository.insert(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A failed write must not kill the writer thread, or logging stops silently for the
                // rest of the process's life.
                log.warn("Query log write failed, dropping {} record(s): {}", batch.size(), e.getMessage());
                dropped.addAndGet(batch.size());
            } finally {
                batch.clear();
            }
        }
    }

    @PreDestroy
    void flush() {
        stopping = true;
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Integer intFrom(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Boolean boolFrom(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Boolean b ? b : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Exposed for the API so an operator can tell "nothing happened" from "logging is off". */
    public boolean enabled() {
        return config.enabled();
    }

    public Optional<Long> droppedRecords() {
        long value = dropped.get();
        return value == 0 ? Optional.empty() : Optional.of(value);
    }
}
