package com.travislabs.mjdbcmcp.sql;

import com.travislabs.mjdbcmcp.datasource.Capability;
import java.util.List;

/**
 * What a piece of agent-written SQL turned out to be: the Capability it requires, and the tables it
 * names so the Object Allowlist can be checked against the same parse (ADR-0004, ADR-0009).
 *
 * @param capability     the Capability the statement requires
 * @param verb           the statement class as the parser saw it, for the refusal message
 * @param tables         every table the statement names, schema-qualified where the SQL qualified it
 * @param tablesResolved false when the parser could not enumerate them. Classification still
 *                       succeeded — the Capability is known — but the Object Allowlist has nothing
 *                       to check, so a scoped Datasource must refuse and an unrestricted one need
 *                       not care (ADR-0009)
 * @param modifyingCte   true when a WITH clause contained a write — recorded because a refusal for
 *                       this reason is otherwise baffling to an agent that wrote what looks like a SELECT
 */
public record Classification(
        Capability capability,
        String verb,
        List<QualifiedName> tables,
        boolean tablesResolved,
        boolean modifyingCte) {

    public Classification {
        tables = List.copyOf(tables);
    }

    /** A table name as the SQL wrote it. {@code schema} is null when the SQL did not qualify it. */
    public record QualifiedName(String schema, String table) {
        @Override
        public String toString() {
            return schema == null ? table : schema + "." + table;
        }
    }
}
