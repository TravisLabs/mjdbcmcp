package com.travislabs.mjdbcmcp.datasource;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A permission attached to a Datasource, naming a class of statement it may run. Granted by the
 * operator, never requested by the agent (ADR-0001).
 *
 * <p>Capabilities are not a security boundary — see ADR-0003. They prevent honest mistakes and
 * narrow what a well-behaved agent attempts.
 */
public enum Capability {

    /** Reads. The only Capability that leaves a Connection read-only at the engine. */
    SELECT,

    /** INSERT, UPDATE, DELETE, MERGE — statements that change rows. */
    DML,

    /** CREATE of any object. */
    DDL_CREATE,

    /** ALTER of any object. */
    DDL_ALTER,

    /**
     * DROP of any object, and TRUNCATE. Truncation is grouped here rather than under DML because it
     * discards data irrecoverably and without a WHERE clause to get wrong — an operator granting
     * "may change rows" is not thereby granting "may empty a table".
     */
    DDL_DROP;

    /**
     * True when this Capability permits writing; a Datasource with none of these stays read-only.
     *
     * @return true if this capability allows modifications (non-SELECT), false otherwise
     */
    public boolean isWrite() {
        return this != SELECT;
    }

    /**
     * Returns the lowercase wire name of this Capability used in REST payloads and tool metadata.
     *
     * @return the lowercase wire name
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the comma-separated form stored in the application database and sent over REST.
     *
     * @param csv comma-separated capability names
     * @return set of parsed {@link Capability} values
     */
    public static Set<Capability> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Capability.valueOf(s.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Formats a set of Capabilities as a comma-separated string of wire names.
     *
     * @param capabilities set of capabilities to format
     * @return comma-separated wire names
     */
    public static String format(Set<Capability> capabilities) {
        return capabilities.stream().map(Capability::wireName).collect(Collectors.joining(","));
    }
}
