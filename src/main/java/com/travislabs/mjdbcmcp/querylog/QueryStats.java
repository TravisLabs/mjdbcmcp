package com.travislabs.mjdbcmcp.querylog;

import java.util.List;

/**
 * The dashboard's numbers, over the retained window.
 *
 * @param windowFrom      earliest record the figures cover, or null when there are none — without it
 *                        "12 calls" is unreadable, because retention may have eaten the rest
 * @param droppedRecords  records the writer queue could not accept. Surfaced rather than swallowed:
 *                        a dashboard that quietly under-reports is worse than one that says so
 */
public record QueryStats(
        Summary overall,
        List<Group> byDatasource,
        List<Group> byTool,
        List<RefusalCount> refusals,
        List<QueryExecution> slowest,
        String windowFrom,
        long droppedRecords) {

    /**
     * @param p50Ms median duration; null when the window is empty
     * @param p95Ms the number that actually tells an operator whether things are slow — an average
     *              hides the tail that an agent waits on
     */
    public record Summary(
            long calls,
            long ok,
            long refused,
            long failed,
            long cancelled,
            Long avgMs,
            Long p50Ms,
            Long p95Ms,
            Long maxMs,
            long rowsReturned) {

        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0, null, null, null, null, 0);
        }
    }

    /** One row of a breakdown: a datasource name, or a tool name. */
    public record Group(String name, Summary summary) {
    }

    public record RefusalCount(String kind, long count) {
    }
}
