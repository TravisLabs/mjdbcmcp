package com.travislabs.mjdbcmcp.datasource;

/** Live HikariCP counters for one Datasource's Pool. */
public record PoolStats(
        String datasource,
        int total,
        int active,
        int idle,
        int awaiting,
        int max) {
}
