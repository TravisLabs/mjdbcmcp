package com.travislabs.mjdbcmcp.querylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.travislabs.mjdbcmcp.Refusal;
import com.travislabs.mjdbcmcp.config.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stats maths and retention, against a real SQLite file — the same engine production uses, so the
 * percentile-by-offset trick is exercised rather than assumed.
 */
class QueryLogServiceTest {

    /** Temporary directory for application SQLite database. */
    @TempDir
    Path configDir;

    /** SQLite datasource connection pool. */
    private HikariDataSource dataSource;
    /** Query log repository instance. */
    private QueryLogRepository repository;
    /** Query log service under test. */
    private QueryLogService service;

    /** Sets up database schema and services before each test. */
    @BeforeEach
    void setUp() throws IOException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + configDir.resolve("test.db"));
        cfg.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(cfg);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        for (String statement : schema().split(";")) {
            if (!statement.isBlank()) {
                jdbc.sql(statement).update();
            }
        }
        repository = new QueryLogRepository(jdbc);
        service = new QueryLogService(repository, props(true, true));
    }

    /** Closes the database pool after each test. */
    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    /** Verifies aggregation of query execution outcomes and summary counters. */
    @Test
    void recordsOutcomesAndCountsThem() {
        record("demo", "raw_query", "SELECT 1", Map.of("rowCount", 3));
        record("demo", "raw_query", "SELECT 2", Map.of("rowCount", 7));
        refuse("demo", "raw_execute", Refusal.Kind.CAPABILITY);
        fail("demo", "raw_query");
        cancel("demo", "raw_query", "Operation was aborted");
        cancelViaSqlException("demo", "raw_query");
        awaitWritten(6);

        var stats = service.stats(Duration.ofMinutes(10), 5);

        assertThat(stats.overall().calls()).isEqualTo(6);
        assertThat(stats.overall().ok()).isEqualTo(2);
        assertThat(stats.overall().refused()).isEqualTo(1);
        assertThat(stats.overall().failed()).isEqualTo(1);
        assertThat(stats.overall().cancelled()).isEqualTo(2);
        assertThat(stats.overall().rowsReturned()).isEqualTo(10);
    }

    /** Verifies session-scoped cancellation signals interrupt execution and record cancelled outcomes. */
    @Test
    void cancelSessionSignalsInFlightQueries() {
        var handle = service.begin("demo", "raw_query", "SELECT pg_sleep(10)", "test-session-123");
        assertThat(service.running()).hasSize(1);

        service.cancelSession("test-session-123", 42, "AbortError: The operation was aborted.");
        handle.failed(new RuntimeException("Interrupted during query"));

        awaitWritten(1);
        assertThat(service.running()).isEmpty();
        var recent = repository.recent(Instant.now().minus(Duration.ofMinutes(1)), 10);
        assertThat(recent).singleElement().satisfies(q -> {
            assertThat(q.outcome()).isEqualTo(QueryExecution.Outcome.CANCELLED);
            assertThat(q.error()).contains("Interrupted during query");
        });
    }

    /** Verifies refusal statistics grouping by refusal kind. */
    @Test
    void refusalsAreBrokenDownByKind() {
        refuse("demo", "raw_execute", Refusal.Kind.CAPABILITY);
        refuse("demo", "raw_execute", Refusal.Kind.CAPABILITY);
        refuse("demo", "raw_query", Refusal.Kind.NOT_ALLOWLISTED);
        awaitWritten(3);

        assertThat(service.stats(Duration.ofMinutes(10), 5).refusals())
                .extracting(QueryStats.RefusalCount::kind, QueryStats.RefusalCount::count)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("capability", 2L),
                        org.assertj.core.api.Assertions.tuple("not_allowlisted", 1L));
    }

    /** Verifies p50 and p95 percentile calculations from recorded durations. */
    @Test
    void percentilesComeFromTheRealDistribution() {
        // 100 records with durations 1..100ms, written directly so the timings are exact.
        List<QueryExecution> batch = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            batch.add(new QueryExecution(null, "demo", "raw_query", "SELECT " + i, Instant.now(), i,
                    QueryExecution.Outcome.OK, null, null, 1, false, null));
        }
        repository.insert(batch);

        var overall = service.stats(Duration.ofMinutes(10), 5).overall();

        assertThat(overall.calls()).isEqualTo(100);
        assertThat(overall.maxMs()).isEqualTo(100);
        // p50 and p95 are the values at those ranks, not an average pretending to be one.
        assertThat(overall.p50Ms()).isBetween(50L, 51L);
        assertThat(overall.p95Ms()).isBetween(95L, 96L);
    }

    /** Verifies activity statistics grouping by datasource and tool name. */
    @Test
    void breakdownsSplitByDatasourceAndTool() {
        record("alpha", "raw_query", "SELECT 1", Map.of("rowCount", 1));
        record("beta", "raw_query", "SELECT 1", Map.of("rowCount", 2));
        record("beta", "describe_table", null, Map.of());
        awaitWritten(3);

        var stats = service.stats(Duration.ofMinutes(10), 5);

        assertThat(stats.byDatasource()).extracting(QueryStats.Group::name).containsExactly("alpha", "beta");
        assertThat(stats.byDatasource()).filteredOn(g -> g.name().equals("beta"))
                .first().extracting(g -> g.summary().calls()).isEqualTo(2L);
        assertThat(stats.byTool()).extracting(QueryStats.Group::name)
                .containsExactly("describe_table", "raw_query");
    }

    /** Verifies that in-flight queries appear in the running list and disappear once finished. */
    @Test
    void runningQueriesAppearWhileInFlightAndVanishAfter() {
        var handle = service.begin("demo", "raw_query", "SELECT pg_sleep(10)");

        assertThat(service.running())
                .singleElement()
                .satisfies(q -> {
                    assertThat(q.datasource()).isEqualTo("demo");
                    assertThat(q.tool()).isEqualTo("raw_query");
                    assertThat(q.sql()).isEqualTo("SELECT pg_sleep(10)");
                });

        handle.ok(Map.of("rowCount", 1));
        assertThat(service.running()).isEmpty();
    }

    /** Verifies descending sort order by elapsed duration for in-flight queries. */
    @Test
    void runningQueriesAreSortedLongestFirst() throws InterruptedException {
        var first = service.begin("demo", "raw_query", "old");
        Thread.sleep(20);
        var second = service.begin("demo", "raw_query", "new");

        assertThat(service.running()).extracting(RunningQuery::sql).containsExactly("old", "new");

        first.ok(Map.of());
        second.ok(Map.of());
    }

    /** Verifies pruning of expired records and enforcement of table row caps. */
    @Test
    void retentionDropsOldRecordsAndEnforcesTheRowCap() {
        Instant old = Instant.now().minus(Duration.ofDays(30));
        repository.insert(List.of(
                execution(old, 5), execution(old, 6),
                execution(Instant.now(), 7), execution(Instant.now(), 8), execution(Instant.now(), 9)));
        assertThat(repository.count()).isEqualTo(5);

        repository.prune(Instant.now().minus(Duration.ofDays(7)), 100);
        assertThat(repository.count()).as("aged out").isEqualTo(3);

        repository.prune(Instant.now().minus(Duration.ofDays(7)), 2);
        assertThat(repository.count()).as("row cap").isEqualTo(2);
    }

    /** Verifies that disabled logging tracks active queries in memory without database persistence. */
    @Test
    void disabledLoggingStillTracksRunningQueries() {
        var disabled = new QueryLogService(repository, props(false, true));
        var handle = disabled.begin("demo", "raw_query", "SELECT 1");

        assertThat(disabled.running()).hasSize(1);
        handle.ok(Map.of());

        assertThat(disabled.running()).isEmpty();
        assertThat(repository.count()).as("nothing written when disabled").isZero();
    }

    /** Verifies paginated retrieval of recent query execution records. */
    @Test
    void recentSupportsPaginationWithLimitAndOffset() {
        for (int i = 1; i <= 5; i++) {
            record("demo", "raw_query", "SELECT " + i, Map.of("rowCount", i));
        }
        awaitWritten(5);

        var page1 = service.recent(Duration.ofMinutes(5), 2, 0);
        var page2 = service.recent(Duration.ofMinutes(5), 2, 2);
        var page3 = service.recent(Duration.ofMinutes(5), 2, 4);
        var emptyPage = service.recent(Duration.ofMinutes(5), 2, 6);

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page3).hasSize(1);
        assertThat(emptyPage).isEmpty();

        // Most recent first (SELECT 5, SELECT 4, ...)
        assertThat(page1).extracting(QueryExecution::sql).containsExactly("SELECT 5", "SELECT 4");
        assertThat(page2).extracting(QueryExecution::sql).containsExactly("SELECT 3", "SELECT 2");
        assertThat(page3).extracting(QueryExecution::sql).containsExactly("SELECT 1");
    }

    /** Verifies that statement SQL is withheld when storeSql is disabled. */
    @Test
    void sqlIsWithheldWhenStoreSqlIsOff() {
        var noSql = new QueryLogService(repository, props(true, false));
        noSql.begin("demo", "raw_query", "SELECT secret FROM t").ok(Map.of("rowCount", 1));

        await().atMost(5, TimeUnit.SECONDS).until(() -> repository.count() == 1);
        assertThat(repository.recent(Instant.now().minus(Duration.ofMinutes(5)), 10))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.sql()).isNull();
                    assertThat(e.tool()).as("the call is still recorded").isEqualTo("raw_query");
                });
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private void record(String datasource, String tool, String sql, Map<String, Object> payload) {
        service.begin(datasource, tool, sql).ok(payload);
    }

    private void refuse(String datasource, String tool, Refusal.Kind kind) {
        service.begin(datasource, tool, "SELECT 1").refused(new Refusal(kind, "no"));
    }

    private void fail(String datasource, String tool) {
        service.begin(datasource, tool, "SELECT 1").failed(new IllegalStateException("boom"));
    }

    private void cancel(String datasource, String tool, String reason) {
        service.begin(datasource, tool, "SELECT 1").cancelled(reason);
    }

    private void cancelViaSqlException(String datasource, String tool) {
        service.begin(datasource, tool, "SELECT 1").failed(
                new java.sql.SQLException("ERROR: canceling statement due to user request", "57014"));
    }

    private void awaitWritten(int expected) {
        await().atMost(5, TimeUnit.SECONDS).until(() -> repository.count() >= expected);
    }

    private static QueryExecution execution(Instant at, int durationMs) {
        return new QueryExecution(null, "demo", "raw_query", "SELECT 1", at, durationMs,
                QueryExecution.Outcome.OK, null, null, 1, false, null);
    }

    private AppProperties.QueryLog props(boolean enabled, boolean storeSql) {
        return new AppProperties.QueryLog(enabled, storeSql, 4000, Duration.ofDays(7), 50_000, 100);
    }

    /**
     * The real schema, so these tests break when it drifts. Comment lines are stripped before
     * splitting on semicolons, because the comments contain semicolons of their own.
     */
    private String schema() throws IOException {
        String raw = Files.readString(Path.of("src/main/resources/db/schema.sql"), StandardCharsets.UTF_8);
        return raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
