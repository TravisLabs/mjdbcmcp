package com.travislabs.mjdbcmcp.datasource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The set of schemas and tables a Datasource may name. Empty means unrestricted.
 *
 * <p>It constrains every tool regardless of how the statement was produced, and it also filters
 * introspection so an un-allowlisted table is invisible rather than merely refused (ADR-0009).
 *
 * <p>Entry syntax, one per line or comma-separated:
 * <pre>
 *   sales.orders     schema-qualified table
 *   sales.*          every table in a schema
 *   orders           unqualified — resolved against the Datasource's default schema
 *   "Sales"."Orders" quoted parts match case-sensitively; unquoted parts fold case
 * </pre>
 *
 * <p>This scopes what SQL may <em>name</em>, not what the engine may ultimately <em>reach</em>: a
 * view, trigger or cascade can still touch objects the list never mentions. Like Capabilities it is
 * accident resistance (ADR-0003).
 */
public record ObjectAllowlist(List<Entry> entries) {

    /** Constant singleton instance representing an unrestricted allowlist. */
    private static final ObjectAllowlist UNRESTRICTED = new ObjectAllowlist(List.of());

    public ObjectAllowlist {
        entries = List.copyOf(entries);
    }

    /**
     * Returns an unrestricted Object Allowlist instance permitting all tables and schemas.
     *
     * @return unrestricted allowlist
     */
    public static ObjectAllowlist unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * Checks if this allowlist imposes no restrictions on schemas or tables.
     *
     * @return true if unrestricted, false if scoped
     */
    public boolean isUnrestricted() {
        return entries.isEmpty();
    }

    /**
     * @param schema the schema the name resolved to, or null when the statement did not qualify it
     *               and the Datasource has no default schema
     */
    public boolean permitsTable(String schema, String table) {
        if (isUnrestricted()) {
            return true;
        }
        return entries.stream().anyMatch(e -> e.matchesTable(schema, table));
    }

    /**
     * True when any entry could name something in this schema — the test {@code list_schemas} uses,
     * so a schema with no reachable table does not appear at all.
     */
    public boolean permitsSchema(String schema) {
        if (isUnrestricted()) {
            return true;
        }
        return entries.stream().anyMatch(e -> e.matchesSchema(schema));
    }

    /** Parses the stored form. Blank input yields an unrestricted list. */
    public static ObjectAllowlist parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNRESTRICTED;
        }
        List<Entry> parsed = new ArrayList<>();
        for (String token : raw.split("[,\\n\\r]")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(Entry.parse(trimmed));
            }
        }
        return parsed.isEmpty() ? UNRESTRICTED : new ObjectAllowlist(parsed);
    }

    /**
     * Formats the allowlist entries into a newline-separated string.
     *
     * @return newline-separated allowlist entries
     */
    public String format() {
        return entries.stream().map(Entry::format).collect(Collectors.joining("\n"));
    }

    /**
     * One allowlist line. A null {@code schema} means the entry was unqualified, which matches a
     * name in any schema — the Datasource's default schema is applied to the <em>name being
     * checked</em>, not to the entry, so an unqualified entry does not silently widen when the
     * default schema changes.
     */
    public record Entry(String schema, boolean schemaQuoted, String table, boolean tableQuoted) {

        /**
         * Parses a single allowlist token into an Entry.
         *
         * @param token token string (e.g. {@code sales.orders}, {@code sales.*}, or {@code orders})
         * @return parsed Entry instance
         */
        static Entry parse(String token) {
            List<String> parts = splitQualified(token);
            if (parts.size() == 1) {
                String table = parts.get(0);
                return new Entry(null, false, unquote(table), isQuoted(table));
            }
            String schema = parts.get(0);
            String table = parts.get(1);
            return new Entry(unquote(schema), isQuoted(schema), unquote(table), isQuoted(table));
        }

        /**
         * Checks whether this entry permits the given candidate schema.
         *
         * @param candidateSchema candidate schema name
         * @return true if permitted
         */
        boolean matchesSchema(String candidateSchema) {
            // An unqualified entry says nothing about schemas, so it cannot exclude one.
            return schema == null || equal(schema, schemaQuoted, candidateSchema);
        }

        /**
         * Checks whether this entry permits the given table in the candidate schema.
         *
         * @param candidateSchema candidate schema name
         * @param candidateTable  candidate table name
         * @return true if permitted
         */
        boolean matchesTable(String candidateSchema, String candidateTable) {
            if (!matchesSchema(candidateSchema)) {
                return false;
            }
            return "*".equals(table) || equal(table, tableQuoted, candidateTable);
        }

        /**
         * Formats this entry as a string.
         *
         * @return formatted entry string
         */
        String format() {
            String t = tableQuoted ? '"' + table + '"' : table;
            if (schema == null) {
                return t;
            }
            return (schemaQuoted ? '"' + schema + '"' : schema) + "." + t;
        }

        /** Quoted parts match literally; unquoted parts fold case, as unquoted SQL identifiers do. */
        private static boolean equal(String entryPart, boolean quoted, String candidate) {
            if (candidate == null) {
                return false;
            }
            return quoted ? entryPart.equals(candidate) : entryPart.equalsIgnoreCase(candidate);
        }

        private static boolean isQuoted(String part) {
            return part.length() >= 2 && part.charAt(0) == '"' && part.charAt(part.length() - 1) == '"';
        }

        private static String unquote(String part) {
            return isQuoted(part) ? part.substring(1, part.length() - 1) : part;
        }

        /** Splits on the dot that separates schema from table, ignoring dots inside quotes. */
        private static List<String> splitQualified(String token) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (ch == '"') {
                    inQuotes = !inQuotes;
                    current.append(ch);
                } else if (ch == '.' && !inQuotes) {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
            parts.add(current.toString());
            // Anything deeper than schema.table (catalog.schema.table) keeps the last two parts.
            return parts.size() <= 2 ? parts : parts.subList(parts.size() - 2, parts.size());
        }
    }

    /** Normalizes an unqualified name against a Datasource's default schema for checking. */
    public static String resolveSchema(String statementSchema, String defaultSchema) {
        if (statementSchema != null && !statementSchema.isBlank()) {
            return statementSchema;
        }
        return defaultSchema == null || defaultSchema.isBlank() ? null : defaultSchema;
    }

    @Override
    public String toString() {
        return isUnrestricted() ? "unrestricted"
                : entries.stream().map(Entry::format).collect(Collectors.joining(", "));
    }
}
