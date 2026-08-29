package com.travislabs.mjdbcmcp.sql;

import com.travislabs.mjdbcmcp.Refusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the SELECT behind the Structured {@code query} tool from typed arguments — the agent names
 * a table, columns, filters and an order, and never supplies SQL text (ADR-0008).
 *
 * <p>Because it builds the statement, it never goes through Classification: it is a select by
 * construction. Identifiers are quoted with the driver's own quote string and every filter value is
 * bound, so a name can be accepted without accepting SQL.
 */
public final class StructuredQuery {

    /** Comparison operators the tool exposes. Anything else is a refusal, not an escape hatch. */
    private static final Set<String> BINARY_OPERATORS = Set.of("=", "<>", "!=", "<", "<=", ">", ">=", "LIKE", "NOT LIKE");
    private static final Set<String> NULLARY_OPERATORS = Set.of("IS NULL", "IS NOT NULL");
    private static final Set<String> LIST_OPERATORS = Set.of("IN", "NOT IN");

    /** @param sql the statement to prepare, @param params values to bind in order */
    public record Built(String sql, List<Object> params) {
    }

    private StructuredQuery() {
    }

    @SuppressWarnings("unchecked")
    public static Built build(String quote, String schema, String table, List<String> columns,
                              List<Map<String, Object>> filters, List<Map<String, Object>> orderBy, Integer limit) {
        if (table == null || table.isBlank()) {
            throw new Refusal(Refusal.Kind.BAD_ARGUMENT, "A table name is required.");
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        if (columns == null || columns.isEmpty()) {
            sql.append('*');
        } else {
            List<String> quoted = new ArrayList<>();
            for (String column : columns) {
                quoted.add(quoteIdentifier(quote, column));
            }
            sql.append(String.join(", ", quoted));
        }
        sql.append(" FROM ");
        if (schema != null && !schema.isBlank()) {
            sql.append(quoteIdentifier(quote, schema)).append('.');
        }
        sql.append(quoteIdentifier(quote, table));

        List<Object> params = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            List<String> predicates = new ArrayList<>();
            for (Map<String, Object> filter : filters) {
                predicates.add(predicate(quote, filter, params));
            }
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }

        if (orderBy != null && !orderBy.isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (Map<String, Object> term : orderBy) {
                String column = string(term.get("column"), "orderBy.column");
                String direction = term.get("direction") == null ? "ASC"
                        : String.valueOf(term.get("direction")).toUpperCase(Locale.ROOT);
                if (!direction.equals("ASC") && !direction.equals("DESC")) {
                    throw new Refusal(Refusal.Kind.BAD_ARGUMENT,
                            "Order direction must be ASC or DESC, not '" + direction + "'.");
                }
                terms.add(quoteIdentifier(quote, column) + " " + direction);
            }
            sql.append(" ORDER BY ").append(String.join(", ", terms));
        }

        // No LIMIT clause is appended: the row cap is setMaxRows, which needs no dialect (ADR-0007).
        return new Built(sql.toString(), params);
    }

    private static String predicate(String quote, Map<String, Object> filter, List<Object> params) {
        String column = quoteIdentifier(quote, string(filter.get("column"), "filter.column"));
        String operator = filter.get("operator") == null ? "="
                : String.valueOf(filter.get("operator")).trim().toUpperCase(Locale.ROOT);
        Object value = filter.get("value");

        if (NULLARY_OPERATORS.contains(operator)) {
            return column + " " + operator;
        }
        if (LIST_OPERATORS.contains(operator)) {
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw new Refusal(Refusal.Kind.BAD_ARGUMENT,
                        "Operator " + operator + " needs a non-empty list as its value.");
            }
            List<String> placeholders = new ArrayList<>();
            for (Object element : list) {
                placeholders.add("?");
                params.add(element);
            }
            return column + " " + operator + " (" + String.join(", ", placeholders) + ")";
        }
        if (!BINARY_OPERATORS.contains(operator)) {
            throw new Refusal(Refusal.Kind.BAD_ARGUMENT,
                    "Unsupported operator '" + operator + "'. Use one of " + BINARY_OPERATORS
                            + ", " + LIST_OPERATORS + " or " + NULLARY_OPERATORS
                            + ", or use raw_query for anything more involved.");
        }
        params.add(value);
        return column + " " + operator + " ?";
    }

    /**
     * Quotes an identifier with the driver's own quote string, doubling any embedded quote. A driver
     * that reports a blank quote string has no quoting, so the identifier must be a plain word.
     */
    static String quoteIdentifier(String quote, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new Refusal(Refusal.Kind.BAD_ARGUMENT, "An identifier was empty.");
        }
        if (quote == null || quote.isBlank()) {
            if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new Refusal(Refusal.Kind.BAD_ARGUMENT,
                        "This driver reports no identifier quoting, so '" + identifier
                                + "' cannot be used safely as a name.");
            }
            return identifier;
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static String string(Object value, String what) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new Refusal(Refusal.Kind.BAD_ARGUMENT, what + " is required.");
        }
        return String.valueOf(value);
    }
}
