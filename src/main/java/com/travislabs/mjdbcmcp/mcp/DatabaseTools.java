package com.travislabs.mjdbcmcp.mcp;

import com.travislabs.mjdbcmcp.Refusal;
import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.DatasourceService;
import com.travislabs.mjdbcmcp.datasource.ObjectAllowlist;
import com.travislabs.mjdbcmcp.querylog.QueryLogService;
import com.travislabs.mjdbcmcp.sql.Classification;
import com.travislabs.mjdbcmcp.sql.MetadataReader;
import com.travislabs.mjdbcmcp.sql.ScriptClassification;
import com.travislabs.mjdbcmcp.sql.SqlClassifier;
import com.travislabs.mjdbcmcp.sql.StatementRunner;
import com.travislabs.mjdbcmcp.sql.StructuredQuery;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Tool Surface. Structured Tools cover reads (ADR-0008); writes go through {@code raw_execute},
 * which runs atomic scripts inside isolated transactions (ADR-0013). Which tools exist at all is
 * derived from Capabilities granted across enabled Datasources (ADR-0001) — see {@link ToolSurface}.
 */
@Component
public class DatabaseTools {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTools.class);

    private static final String DATASOURCE_ARG =
            "\"datasource\":{\"type\":\"string\",\"description\":\"Name of a configured datasource; "
                    + "list_datasources shows them with their capabilities\"}";

    private final DatasourceService datasources;
    private final SqlClassifier classifier;
    private final StatementRunner runner;
    private final MetadataReader metadata;
    private final QueryLogService queryLog;
    private final McpJsonMapper json;

    public DatabaseTools(DatasourceService datasources,
                         SqlClassifier classifier, StatementRunner runner, MetadataReader metadata,
                         QueryLogService queryLog, McpJsonMapper json) {
        this.datasources = datasources;
        this.classifier = classifier;
        this.runner = runner;
        this.metadata = metadata;
        this.queryLog = queryLog;
        this.json = json;
    }

    // ── Structured Tools: always present, they need no Capability beyond existing ──────────────

    public SyncToolSpecification listDatasources() {
        return tool("list_datasources",
                "List the configured datasources, with the capabilities, enabled tools, and object scope of each. "
                        + "Start here: a datasource's capabilities decide which statements it will run.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (exchange, req) -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Datasource d : datasources.findEnabled()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", d.name());
                        row.put("description", d.description() == null ? "" : d.description());
                        row.put("url", redact(d.jdbcUrl()));
                        row.put("capabilities", d.capabilities().stream().map(Capability::wireName).sorted().toList());
                        row.put("disabledTools", d.disabledTools().stream().sorted().toList());
                        row.put("scope", d.allowlist().isUnrestricted() ? "unrestricted" : d.allowlist().toString());
                        row.put("defaultSchema", d.defaultSchema());
                        row.put("maxRows", d.maxRows());
                        rows.add(row);
                    }
                    return reply(null, Map.of("datasources", rows));
                });
    }

    public SyncToolSpecification databaseInfo() {
        return tool("database_info",
                "Product, version and driver of the database behind a datasource.",
                object(DATASOURCE_ARG, "\"datasource\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "database_info");
                    try (var lease = datasources.lease(d)) {
                        return reply(d, metadata.databaseInfo(lease.connection()));
                    }
                });
    }

    public SyncToolSpecification listSchemas() {
        return tool("list_schemas",
                "List the schemas visible on a datasource, filtered to its configured object scope.",
                object(DATASOURCE_ARG, "\"datasource\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "list_schemas");
                    try (var lease = datasources.lease(d)) {
                        return reply(d, Map.of("schemas", metadata.schemas(lease.connection(), d)));
                    }
                });
    }

    public SyncToolSpecification listTables() {
        return tool("list_tables",
                "List tables and views on a datasource, filtered to its configured object scope. "
                        + "Optionally narrow by schema, name pattern or type.",
                object(DATASOURCE_ARG + ","
                        + "\"schema\":{\"type\":\"string\",\"description\":\"Schema to search; defaults to the datasource's default schema\"},"
                        + "\"namePattern\":{\"type\":\"string\",\"description\":\"SQL LIKE pattern, e.g. 'order_%'\"},"
                        + "\"types\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"JDBC table types; defaults to TABLE and VIEW\"}",
                        "\"datasource\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "list_tables");
                    String schema = strOr(req, "schema", d.defaultSchema());
                    try (var lease = datasources.lease(d)) {
                        return reply(d, Map.of("tables",
                                metadata.tables(lease.connection(), d, schema, str(req, "namePattern"), strList(req, "types"))));
                    }
                });
    }

    public SyncToolSpecification describeTable() {
        return tool("describe_table",
                "Columns, primary key, foreign keys and indexes for one table.",
                object(DATASOURCE_ARG + ","
                        + "\"table\":{\"type\":\"string\",\"description\":\"Table name\"},"
                        + "\"schema\":{\"type\":\"string\",\"description\":\"Schema; defaults to the datasource's default schema\"}",
                        "\"datasource\",\"table\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "describe_table");
                    String table = str(req, "table");
                    String schema = strOr(req, "schema", d.defaultSchema());
                    datasources.requireAllowlisted(d, ObjectAllowlist.resolveSchema(schema, d.defaultSchema()), table);
                    try (var lease = datasources.lease(d)) {
                        return reply(d, metadata.describeTable(lease.connection(), schema, table));
                    }
                });
    }

    // ── Structured read: builds its own SELECT, so it never goes through Classification ────────

    public SyncToolSpecification query() {
        return tool("query",
                "Read rows from one table with typed filters — no SQL text. Rows come back as arrays "
                        + "under a single column list, capped by the datasource's row limit.",
                object(DATASOURCE_ARG + ","
                        + "\"table\":{\"type\":\"string\",\"description\":\"Table to read\"},"
                        + "\"schema\":{\"type\":\"string\",\"description\":\"Schema; defaults to the datasource's default schema\"},"
                        + "\"columns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Columns to return; all of them when omitted\"},"
                        + "\"filters\":{\"type\":\"array\",\"description\":\"ANDed predicates\",\"items\":{\"type\":\"object\",\"properties\":{"
                        + "\"column\":{\"type\":\"string\"},"
                        + "\"operator\":{\"type\":\"string\",\"description\":\"= <> < <= > >= LIKE, NOT LIKE, IN, NOT IN, IS NULL, IS NOT NULL\"},"
                        + "\"value\":{\"description\":\"Bound value; a list for IN and NOT IN; omitted for IS NULL\"}},"
                        + "\"required\":[\"column\"]}},"
                        + "\"orderBy\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                        + "\"column\":{\"type\":\"string\"},\"direction\":{\"type\":\"string\",\"enum\":[\"ASC\",\"DESC\"]}},"
                        + "\"required\":[\"column\"]}},"
                        + "\"maxRows\":{\"type\":\"integer\",\"description\":\"Row cap for this call; never exceeds the datasource's own limit\"}",
                        "\"datasource\",\"table\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "query");
                    datasources.requireCapability(d, Capability.SELECT, "queries");
                    String table = str(req, "table");
                    String schema = strOr(req, "schema", d.defaultSchema());
                    datasources.requireAllowlisted(d, ObjectAllowlist.resolveSchema(schema, d.defaultSchema()), table);
                    try (var lease = datasources.lease(d)) {
                        String quote = lease.connection().getMetaData().getIdentifierQuoteString();
                        var built = StructuredQuery.build(quote, schema, table, strList(req, "columns"),
                                mapList(req, "filters"), mapList(req, "orderBy"), integer(req, "maxRows"));
                        return reply(d, runner.query(lease.connection(), d, built.sql(), built.params(),
                                integer(req, "maxRows")));
                    }
                });
    }

    // ── Raw SQL Reads ──────────────────────────────────────────────────────────────────────────

    public SyncToolSpecification rawQuery() {
        return tool("raw_query",
                "Run one read-only SQL statement. Rows come back capped by the datasource's row limit. "
                        + "Refused if the statement turns out to write — including a WITH clause that does. "
                        + "Multi-statement payloads are refused.",
                object(DATASOURCE_ARG + ","
                        + "\"sql\":{\"type\":\"string\",\"description\":\"A single read-only statement\"},"
                        + "\"params\":{\"type\":\"array\",\"items\":{},\"description\":\"Values for ? placeholders, in order\"},"
                        + "\"maxRows\":{\"type\":\"integer\",\"description\":\"Row cap for this call; never exceeds the datasource's own limit\"}",
                        "\"datasource\",\"sql\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "raw_query");
                    String sql = str(req, "sql");
                    Classification classification = classifier.classify(sql);
                    if (classification.capability() != Capability.SELECT) {
                        throw new Refusal(Refusal.Kind.CAPABILITY, "raw_query runs reads only, and this "
                                + (classification.modifyingCte()
                                        ? "statement performs a " + classification.verb() + " inside its WITH "
                                                + "clause, which is why it is not a read despite starting with SELECT"
                                        : "is a " + classification.verb())
                                + ". It requires the " + classification.capability().wireName()
                                + " capability; use raw_execute on a datasource configured for it.");
                    }
                    datasources.requireCapability(d, Capability.SELECT, "queries");
                    datasources.requireAllowlisted(d, classification);
                    try (var lease = datasources.lease(d)) {
                        return reply(d, runner.query(lease.connection(), d, sql, params(req), integer(req, "maxRows")));
                    }
                });
    }

    public SyncToolSpecification explainQuery() {
        return tool("explain_query",
                "Explain the execution plan for a read-only query as structured JSON. "
                        + "Automatically applies the appropriate engine-specific EXPLAIN syntax.",
                object(DATASOURCE_ARG + ","
                        + "\"sql\":{\"type\":\"string\",\"description\":\"A single read-only SELECT query to explain\"},"
                        + "\"params\":{\"type\":\"array\",\"items\":{},\"description\":\"Values for ? placeholders, in order\"},"
                        + "\"analyze\":{\"type\":\"boolean\",\"description\":\"Whether to execute the query to collect actual runtime statistics (default: false)\"}",
                        "\"datasource\",\"sql\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "explain_query");
                    datasources.requireCapability(d, Capability.SELECT, "query explanations");
                    String sql = str(req, "sql");
                    Classification classification = classifier.classifyExplain(sql);
                    datasources.requireAllowlisted(d, classification);
                    try (var lease = datasources.lease(d)) {
                        return reply(d, runner.explain(lease.connection(), d, sql, params(req), bool(req, "analyze")));
                    }
                });
    }

    // ── Raw SQL Writes & Scripts ───────────────────────────────────────────────────────────────

    public SyncToolSpecification rawExecute() {
        return tool("raw_execute",
                "Run one SQL statement or an atomic multi-statement script that changes data or schema. "
                        + "The entire payload is validated against capabilities and allowlists before "
                        + "running inside an isolated transaction. If any statement fails, the entire script rolls back.",
                object(DATASOURCE_ARG + ","
                        + "\"sql\":{\"type\":\"string\",\"description\":\"A single statement or multi-statement script\"},"
                        + "\"params\":{\"type\":\"array\",\"items\":{},\"description\":\"Values for ? placeholders, in order (single statement only)\"}",
                        "\"datasource\",\"sql\""),
                (exchange, req) -> {
                    Datasource d = datasources.requireEnabled(str(req, "datasource"));
                    datasources.requireToolEnabled(d, "raw_execute");
                    String sql = str(req, "sql");
                    ScriptClassification script = classifier.classifyScript(sql);

                    // Check if entire script is purely reads
                    if (script.requiredCapabilities().isEmpty()
                            || (script.requiredCapabilities().size() == 1
                            && script.requiredCapabilities().contains(Capability.SELECT))) {
                        throw new Refusal(Refusal.Kind.BAD_ARGUMENT,
                                "This is a read; run it with raw_query or query so it goes through a read-only connection.");
                    }

                    // Check capabilities for all write statements
                    for (Capability cap : script.requiredCapabilities()) {
                        if (cap.isWrite()) {
                            datasources.requireCapability(d, cap, "write statements");
                        }
                    }

                    // Check Object Allowlist
                    if (!d.allowlist().isUnrestricted()) {
                        if (!script.allTablesResolved()) {
                            throw new Refusal(Refusal.Kind.NOT_ALLOWLISTED,
                                    "Datasource '" + d.name() + "' is scoped to " + d.allowlist()
                                            + ", and some objects named in this script could not be determined, so it was not run.");
                        }
                        for (Classification.QualifiedName t : script.allTables()) {
                            if (!d.allowlist().permitsTable(ObjectAllowlist.resolveSchema(t.schema(), d.defaultSchema()), t.table())) {
                                throw new Refusal(Refusal.Kind.NOT_ALLOWLISTED,
                                        "Datasource '" + d.name() + "' is scoped to " + d.allowlist()
                                                + ", which does not include " + t + ".");
                            }
                        }
                    }

                    try (var lease = datasources.lease(d)) {
                        return reply(d, runner.executeScript(lease.connection(), d, script, params(req)));
                    }
                });
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────────────────

    private record Reply(Datasource datasource, Map<String, Object> payload) {
    }

    private static Reply reply(Datasource datasource, Map<String, Object> payload) {
        return new Reply(datasource, payload);
    }

    @FunctionalInterface
    private interface Handler {
        Reply handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception;
    }

    private SyncToolSpecification tool(String name, String description, String inputSchema, Handler handler) {
        Tool tool = Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(json, inputSchema)
                .build();
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> guarded = (exchange, request) -> {
            String datasourceName = str(request, "datasource");
            var handle = queryLog.begin(datasourceName, name, str(request, "sql"));
            try {
                Reply result = handler.handle(exchange, request);
                CallToolResult rendered = render(result);
                handle.ok(result.payload());
                return rendered;
            } catch (Refusal refusal) {
                handle.refused(refusal);
                return error(refusal.toAgentMessage());
            } catch (Exception e) {
                handle.failed(e);
                log.debug("Tool {} failed", name, e);
                return error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        };
        return SyncToolSpecification.builder().tool(tool).callHandler(guarded).build();
    }

    private static String object(String properties, String required) {
        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + required
                + "],\"additionalProperties\":false}";
    }

    private CallToolResult render(Reply result) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(result.payload());
        return CallToolResult.builder().addTextContent(json.writeValueAsString(body)).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static String str(CallToolRequest req, String key) {
        Object value = req.arguments() == null ? null : req.arguments().get(key);
        return value == null ? null : value.toString();
    }

    private static String strOr(CallToolRequest req, String key, String fallback) {
        String value = str(req, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Boolean bool(CallToolRequest req, String key) {
        Object value = req.arguments() == null ? null : req.arguments().get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer integer(CallToolRequest req, String key) {
        Object value = req.arguments() == null ? null : req.arguments().get(key);
        return value instanceof Number n ? n.intValue() : null;
    }

    private static List<String> strList(CallToolRequest req, String key) {
        Object value = req.arguments() == null ? null : req.arguments().get(key);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(CallToolRequest req, String key) {
        Object value = req.arguments() == null ? null : req.arguments().get(key);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<Object> params(CallToolRequest req) {
        Object value = req.arguments() == null ? null : req.arguments().get("params");
        return value instanceof List<?> list ? new ArrayList<>(list) : null;
    }

    static String redact(String jdbcUrl) {
        return jdbcUrl == null ? null : jdbcUrl.replaceAll("(?i)(password|pwd)=([^&;]*)", "$1=***");
    }
}
