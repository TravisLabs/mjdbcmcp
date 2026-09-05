package com.travislabs.mjdbcmcp.datasource;

/**
 * Live HikariCP counters for one Datasource's Pool.
 *
 * @param datasource name of the Datasource
 * @param total      total number of connections currently in the pool
 * @param active     number of connections currently in use
 * @param idle       number of idle connections available
 * @param awaiting   number of threads currently waiting for a connection
 * @param max        configured maximum pool size
 */
public record PoolStats(
        String datasource,
        int total,
        int active,
        int idle,
        int awaiting,
        int max) {
}
