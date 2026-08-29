package com.travislabs.mjdbcmcp.sql;

import com.travislabs.mjdbcmcp.Refusal;
import com.travislabs.mjdbcmcp.datasource.Capability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.RollbackStatement;
import net.sf.jsqlparser.statement.SavepointStatement;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterSession;
import net.sf.jsqlparser.statement.alter.AlterSystemStatement;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import net.sf.jsqlparser.statement.create.function.CreateFunction;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.procedure.CreateProcedure;
import net.sf.jsqlparser.statement.create.schema.CreateSchema;
import net.sf.jsqlparser.statement.create.sequence.CreateSequence;
import net.sf.jsqlparser.statement.create.synonym.CreateSynonym;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Works out which Capability a piece of agent-written SQL requires, using a real parser rather than
 * matching the leading keyword (ADR-0004).
 *
 * <p>Fails closed: a parse failure is a refusal rather than a pass-through, and a data-modifying CTE
 * classifies as its write verb. Multi-statement payloads are refused for {@code raw_query} and
 * {@code explain_query}, and accepted as atomic transaction scripts in {@code raw_execute} (ADR-0013).
 */
@Component
public class SqlClassifier {

    /**
     * Classifies a single statement. Throws {@link Refusal.Kind#MULTIPLE_STATEMENTS} if more than one
     * statement is submitted.
     */
    public Classification classify(String sql) {
        Statements parsed = parseStatements(sql);
        if (parsed.size() > 1) {
            throw new Refusal(Refusal.Kind.MULTIPLE_STATEMENTS,
                    "Only one statement may be submitted per call; this payload contained "
                            + parsed.size() + ". Use raw_execute for multi-statement scripts.");
        }
        return classifyStatement(parsed.get(0));
    }

    /**
     * Classifies a multi-statement script for {@code raw_execute}.
     */
    public ScriptClassification classifyScript(String sql) {
        Statements parsed = parseStatements(sql);
        List<Classification> statementClassifications = new ArrayList<>();
        Set<Capability> requiredCapabilities = new LinkedHashSet<>();
        List<Classification.QualifiedName> allTables = new ArrayList<>();
        boolean allTablesResolved = true;

        for (Statement statement : parsed) {
            Classification c = classifyStatement(statement);
            statementClassifications.add(c);
            if (c.capability() != null) {
                requiredCapabilities.add(c.capability());
            }
            allTables.addAll(c.tables());
            if (!c.tablesResolved()) {
                allTablesResolved = false;
            }
        }
        return new ScriptClassification(parsed, statementClassifications, requiredCapabilities,
                allTables, allTablesResolved);
    }

    /**
     * Classifies a query for {@code explain_query}. Must be a single read-only SELECT statement.
     */
    public Classification classifyExplain(String sql) {
        Statements parsed = parseStatements(sql);
        if (parsed.size() > 1) {
            throw new Refusal(Refusal.Kind.MULTIPLE_STATEMENTS,
                    "explain_query accepts only a single query; this payload contained "
                            + parsed.size() + " statements.");
        }
        Statement statement = parsed.get(0);
        if (statement instanceof ExplainStatement explain) {
            statement = explain.getStatement();
            if (statement == null) {
                throw new Refusal(Refusal.Kind.BAD_ARGUMENT, "No inner query found in EXPLAIN statement.");
            }
        }
        if (!(statement instanceof Select select)) {
            throw new Refusal(Refusal.Kind.CAPABILITY,
                    "explain_query only explains read-only queries (SELECT), but this is a "
                            + verbOf(statement) + " statement.");
        }
        Capability cteWrite = modifyingCteCapability(select);
        if (cteWrite != null) {
            throw new Refusal(Refusal.Kind.CAPABILITY,
                    "explain_query only explains read-only queries, and this statement performs a "
                            + verbOf(modifyingCteBody(select)) + " inside its WITH clause.");
        }
        List<Classification.QualifiedName> tables = tablesOf(statement);
        return new Classification(
                Capability.SELECT,
                "EXPLAIN",
                tables == null ? List.of() : tables,
                tables != null,
                false);
    }

    public Classification classifyStatement(Statement statement) {
        if (isTransactionControl(statement)) {
            return new Classification(
                    null, // Transaction control tokens do not require data capabilities
                    verbOf(statement),
                    List.of(),
                    true,
                    false);
        }
        List<Classification.QualifiedName> tables = tablesOf(statement);
        Statement writingCte = modifyingCteBody(statement);
        return new Classification(
                capabilityOf(statement),
                verbOf(writingCte != null ? writingCte : statement),
                tables == null ? List.of() : tables,
                tables != null,
                writingCte != null);
    }

    private Statements parseStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new Refusal(Refusal.Kind.BAD_ARGUMENT, "No SQL was supplied.");
        }
        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(sql);
            if (parsed.isEmpty()) {
                throw new Refusal(Refusal.Kind.BAD_ARGUMENT, "No statement was found in the SQL supplied.");
            }
            return parsed;
        } catch (Refusal r) {
            throw r;
        } catch (Exception e) {
            // Deliberately not a pass-through. The statements a parser chokes on are
            // disproportionately the ones worth stopping; the fix is to teach the parser.
            throw new Refusal(Refusal.Kind.UNPARSEABLE,
                    "This SQL could not be parsed, so it was not run. Rewriting it in more standard "
                            + "syntax usually helps. Parser said: " + rootMessage(e));
        }
    }

    private boolean isTransactionControl(Statement statement) {
        if (statement instanceof Commit || statement instanceof RollbackStatement
                || statement instanceof SavepointStatement || statement instanceof SetStatement) {
            return true;
        }
        String str = statement.toString().trim().toUpperCase(Locale.ROOT);
        return str.startsWith("BEGIN") || str.startsWith("START TRANSACTION")
                || str.startsWith("COMMIT") || str.startsWith("ROLLBACK")
                || str.startsWith("SAVEPOINT");
    }

    public Capability capabilityOf(Statement statement) {
        if (statement instanceof Select select) {
            // A WITH clause that writes makes the whole statement a write, whatever it returns.
            Capability cteWrite = modifyingCteCapability(select);
            return cteWrite != null ? cteWrite : Capability.SELECT;
        }
        if (statement instanceof Insert || statement instanceof Update
                || statement instanceof Delete || statement instanceof Merge) {
            return Capability.DML;
        }
        if (statement instanceof CreateTable || statement instanceof CreateView
                || statement instanceof CreateIndex || statement instanceof CreateSchema
                || statement instanceof CreateSequence || statement instanceof CreateFunction
                || statement instanceof CreateProcedure || statement instanceof CreateSynonym) {
            return Capability.DDL_CREATE;
        }
        if (statement instanceof Alter || statement instanceof AlterSession
                || statement instanceof AlterSystemStatement || statement instanceof RenameTableStatement) {
            return Capability.DDL_ALTER;
        }
        // Truncate sits with DROP, not DML: it discards data irrecoverably and has no WHERE clause
        // to get wrong, so "may change rows" should not imply it. See Capability.DDL_DROP.
        if (statement instanceof Drop || statement instanceof Truncate) {
            return Capability.DDL_DROP;
        }
        if (statement instanceof ExplainStatement explain) {
            Statement inner = explain.getStatement();
            return inner != null ? capabilityOf(inner) : Capability.SELECT;
        }
        // Parsed, but not a class this server knows how to place. Fail closed rather than guess:
        // guessing here is how a GRANT or a CALL ends up running under a read Capability.
        throw new Refusal(Refusal.Kind.UNCLASSIFIABLE,
                "This server does not know what class of statement `" + verbOf(statement)
                        + "` is, so it was not run. Only queries, DML and DDL are classified.");
    }

    /**
     * The write Capability a data-modifying CTE needs, or null when every CTE only reads.
     */
    private Capability modifyingCteCapability(Select select) {
        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems == null) {
            return null;
        }
        for (WithItem<?> item : withItems) {
            if (!(item.getParenthesedStatement() instanceof Statement body)) {
                continue;
            }
            if (body instanceof Select nestedSelect) {
                Capability nested = modifyingCteCapability(nestedSelect);
                if (nested != null) {
                    return nested;
                }
            } else {
                return capabilityOf(body);
            }
        }
        return null;
    }

    /** The writing statement inside a WITH clause, or null when there is not one. */
    private Statement modifyingCteBody(Statement statement) {
        if (!(statement instanceof Select select) || select.getWithItemsList() == null) {
            return null;
        }
        for (WithItem<?> item : select.getWithItemsList()) {
            if (!(item.getParenthesedStatement() instanceof Statement body)) {
                continue;
            }
            if (body instanceof Select nested) {
                Statement deeper = modifyingCteBody(nested);
                if (deeper != null) {
                    return deeper;
                }
            } else {
                return body;
            }
        }
        return null;
    }

    /**
     * Every table the statement names, schema-qualified where the SQL qualified it.
     */
    private List<Classification.QualifiedName> tablesOf(Statement statement) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> cteNames = new LinkedHashSet<>();
        if (!collectTables(statement, names, cteNames)) {
            return null;
        }
        List<Classification.QualifiedName> out = new ArrayList<>();
        for (String name : names) {
            Classification.QualifiedName qualified = split(name);
            boolean isCteAlias = qualified.schema() == null
                    && cteNames.stream().anyMatch(cte -> cte.equalsIgnoreCase(qualified.table()));
            if (!isCteAlias) {
                out.add(qualified);
            }
        }
        return out;
    }

    private boolean collectTables(Statement statement, Set<String> into, Set<String> cteNames) {
        if (statement instanceof Select select
                && select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            List<WithItem<?>> items = new ArrayList<>(select.getWithItemsList());
            boolean outerOk;
            select.setWithItemsList(null);
            try {
                outerOk = addFound(select, into);
            } finally {
                select.setWithItemsList(items);
            }
            if (!outerOk) {
                return false;
            }
            for (WithItem<?> item : items) {
                if (item.getAliasName() != null) {
                    cteNames.add(unquote(item.getAliasName()));
                }
                if (item.getParenthesedStatement() instanceof Statement body
                        && !collectTables(body, into, cteNames)) {
                    return false;
                }
            }
            return true;
        }
        return addFound(statement, into);
    }

    private boolean addFound(Statement statement, Set<String> into) {
        Set<String> found = findTables(statement);
        if (found == null) {
            return false;
        }
        into.addAll(found);
        return true;
    }

    private static String unquote(String identifier) {
        String trimmed = identifier.trim();
        if (trimmed.length() >= 2
                && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '`')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Set<String> findTables(Statement statement) {
        try {
            return new TablesNamesFinder().getTables(statement);
        } catch (Exception primary) {
            try {
                return new TablesNamesFinder().getTablesOrOtherSources(statement);
            } catch (Exception fallback) {
                return null;
            }
        }
    }

    /** Splits {@code schema.table} keeping quoted parts intact; deeper names keep the last two. */
    static Classification.QualifiedName split(String rawName) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < rawName.length(); i++) {
            char ch = rawName.charAt(i);
            if (ch == '"' || ch == '`') {
                inQuotes = !inQuotes;
            } else if (ch == '.' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        if (parts.size() == 1) {
            return new Classification.QualifiedName(null, parts.get(0));
        }
        return new Classification.QualifiedName(parts.get(parts.size() - 2), parts.get(parts.size() - 1));
    }

    /**
     * The statement class in SQL's words rather than the parser's.
     */
    public String verbOf(Statement statement) {
        if (statement instanceof Select) {
            return "SELECT";
        }
        if (statement instanceof Insert) {
            return "INSERT";
        }
        if (statement instanceof Update) {
            return "UPDATE";
        }
        if (statement instanceof Delete) {
            return "DELETE";
        }
        if (statement instanceof Merge) {
            return "MERGE";
        }
        if (statement instanceof CreateTable) {
            return "CREATE TABLE";
        }
        if (statement instanceof CreateView) {
            return "CREATE VIEW";
        }
        if (statement instanceof CreateIndex) {
            return "CREATE INDEX";
        }
        if (statement instanceof CreateSchema) {
            return "CREATE SCHEMA";
        }
        if (statement instanceof CreateSequence) {
            return "CREATE SEQUENCE";
        }
        if (statement instanceof CreateFunction) {
            return "CREATE FUNCTION";
        }
        if (statement instanceof CreateProcedure) {
            return "CREATE PROCEDURE";
        }
        if (statement instanceof CreateSynonym) {
            return "CREATE SYNONYM";
        }
        if (statement instanceof RenameTableStatement) {
            return "RENAME TABLE";
        }
        if (statement instanceof Alter || statement instanceof AlterSession
                || statement instanceof AlterSystemStatement) {
            return "ALTER";
        }
        if (statement instanceof Drop) {
            return "DROP";
        }
        if (statement instanceof Truncate) {
            return "TRUNCATE";
        }
        if (statement instanceof Commit) {
            return "COMMIT";
        }
        if (statement instanceof RollbackStatement) {
            return "ROLLBACK";
        }
        if (statement instanceof SavepointStatement) {
            return "SAVEPOINT";
        }
        if (statement instanceof ExplainStatement) {
            return "EXPLAIN";
        }
        return statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null) {
            return root.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
