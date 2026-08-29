package com.travislabs.mjdbcmcp.sql;

import com.travislabs.mjdbcmcp.datasource.Capability;
import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.statement.Statements;

/**
 * The classification of a multi-statement script submitted to {@code raw_execute}.
 *
 * @param parsedStatements      the parsed AST statements
 * @param classifications       per-statement classifications in order
 * @param requiredCapabilities  union of Capabilities required across all statements in the script
 * @param allTables             all database tables referenced across all statements
 * @param allTablesResolved     false when any statement's tables could not be completely determined
 */
public record ScriptClassification(
        Statements parsedStatements,
        List<Classification> classifications,
        Set<Capability> requiredCapabilities,
        List<Classification.QualifiedName> allTables,
        boolean allTablesResolved) {

    public ScriptClassification {
        classifications = List.copyOf(classifications);
        requiredCapabilities = Set.copyOf(requiredCapabilities);
        allTables = List.copyOf(allTables);
    }
}
