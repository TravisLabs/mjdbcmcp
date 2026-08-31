package com.travislabs.mjdbcmcp.querylog;

import java.time.Instant;

/**
 * One finished tool call, as recorded.
 *
 * <p>Bound parameter values are deliberately absent — see ADR-0012. The statement text is present
 * only when {@code query-log.store-sql} is on.
 */
public record QueryExecution(
        Long id,
        String datasource,
        String tool,
        String sql,
        Instant startedAt,
        long durationMs,
        Outcome outcome,
        String refusalKind,
        String error,
        Integer rowCount,
        Boolean rowCapReached,
        Integer updateCount) {

    public enum Outcome {
        /** The tool did what was asked. */
        OK,
        /** The server declined: a Capability, the Object Allowlist, Classification, or arguments. */
        REFUSED,
        /** Something broke — usually the database saying no. */
        FAILED,
        /** The client or operator cancelled/aborted the operation. */
        CANCELLED;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Outcome parse(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
