package com.travislabs.mjdbcmcp.mcp;

import com.travislabs.mjdbcmcp.config.AppProperties;
import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.DatasourceService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Derives the Tool Surface from the Capabilities and enabled tools across Datasources (ADR-0001,
 * ADR-0013, ADR-0014), and keeps it in step as the operator edits them.
 */
@Component
public class ToolSurface {

    private static final Logger log = LoggerFactory.getLogger(ToolSurface.class);

    /** Database tools provider for constructing tool specifications. */
    private final DatabaseTools tools;
    /** Datasource service for querying enabled datasources and capabilities. */
    private final DatasourceService datasources;
    /** Application configuration properties. */
    private final AppProperties props;
    /** Lazy provider for the active MCP server instance. */
    private final ObjectProvider<McpSyncServer> server;

    /** Currently registered tool names on the live MCP server. */
    private Set<String> current = Set.of();

    /**
     * Constructs the ToolSurface with required dependencies.
     *
     * @param tools       database tools factory
     * @param datasources datasource service
     * @param props       application properties
     * @param server      lazy provider for the running MCP server
     */
    public ToolSurface(DatabaseTools tools, DatasourceService datasources, AppProperties props,
                       ObjectProvider<McpSyncServer> server) {
        this.tools = tools;
        this.datasources = datasources;
        this.props = props;
        this.server = server;
    }

    /** The surface as it should be right now, in a stable order. */
    public List<SyncToolSpecification> specifications() {
        List<Datasource> enabledDs = datasources.findEnabled();

        boolean anyDatabaseInfo = enabledDs.stream().anyMatch(d -> d.isToolEnabled("database_info"));
        boolean anyListSchemas = enabledDs.stream().anyMatch(d -> d.isToolEnabled("list_schemas"));
        boolean anyListTables = enabledDs.stream().anyMatch(d -> d.isToolEnabled("list_tables"));
        boolean anyDescribeTable = enabledDs.stream().anyMatch(d -> d.isToolEnabled("describe_table"));

        boolean anyQuery = enabledDs.stream().anyMatch(d -> d.has(Capability.SELECT) && d.isToolEnabled("query"));
        boolean anyRawQuery = enabledDs.stream().anyMatch(d -> d.has(Capability.SELECT) && d.isToolEnabled("raw_query"));
        boolean anyExplain = enabledDs.stream().anyMatch(d -> d.has(Capability.SELECT) && d.isToolEnabled("explain_query"));
        boolean anyRawExecute = enabledDs.stream().anyMatch(d -> d.hasAnyWriteCapability() && d.isToolEnabled("raw_execute"));

        Map<String, SyncToolSpecification> surface = new LinkedHashMap<>();
        // list_datasources is always present so the agent can discover targets
        put(surface, tools.listDatasources());
        if (anyDatabaseInfo) {
            put(surface, tools.databaseInfo());
        }
        if (anyListSchemas) {
            put(surface, tools.listSchemas());
        }
        if (anyListTables) {
            put(surface, tools.listTables());
        }
        if (anyDescribeTable) {
            put(surface, tools.describeTable());
        }
        if (anyQuery) {
            put(surface, tools.query());
        }
        if (anyRawQuery) {
            put(surface, tools.rawQuery());
        }
        if (anyExplain) {
            put(surface, tools.explainQuery());
        }
        if (anyRawExecute) {
            put(surface, tools.rawExecute());
        }

        // Global disable config can withhold tools server-wide
        for (String disabled : props.mcp().disabledTools()) {
            surface.remove(disabled);
        }
        return List.copyOf(surface.values());
    }

    /**
     * Re-derives the surface and syncs it onto the running server, notifying clients when it moved.
     * Called after any Datasource write.
     */
    public synchronized void refresh() {
        McpSyncServer mcp = server.getIfAvailable();
        if (mcp == null) {
            return;
        }
        List<SyncToolSpecification> desired = specifications();
        Set<String> desiredNames = new java.util.LinkedHashSet<>(desired.stream().map(s -> s.tool().name()).toList());
        if (desiredNames.equals(current)) {
            return;
        }
        for (String gone : current) {
            if (!desiredNames.contains(gone)) {
                mcp.removeTool(gone);
            }
        }
        for (SyncToolSpecification spec : desired) {
            if (!current.contains(spec.tool().name())) {
                mcp.addTool(spec);
            }
        }
        current = desiredNames;
        mcp.notifyToolsListChanged();
        log.info("Tool surface is now {}", desiredNames);
    }

    /** Records the surface the server was built with, so the first refresh diffs against reality. */
    public synchronized void adopt(List<SyncToolSpecification> initial) {
        current = new java.util.LinkedHashSet<>(initial.stream().map(s -> s.tool().name()).toList());
    }

    /**
     * Warns about configurations that look write-capable but are not.
     */
    public List<String> configurationWarnings() {
        List<String> warnings = new ArrayList<>();
        boolean rawExecuteDisabled = props.mcp().disabledTools().contains("raw_execute");
        for (Datasource d : datasources.findEnabled()) {
            if (rawExecuteDisabled && d.hasAnyWriteCapability()) {
                warnings.add("Datasource '" + d.name() + "' grants "
                        + Capability.format(d.capabilities())
                        + " but raw_execute is disabled globally on this server, so nothing can write to it.");
            }
            if (d.capabilities().isEmpty()) {
                warnings.add("Datasource '" + d.name() + "' grants no capabilities, so every "
                        + "statement against it will be refused.");
            }
        }
        return warnings;
    }

    private static void put(Map<String, SyncToolSpecification> surface, SyncToolSpecification spec) {
        surface.put(spec.tool().name(), spec);
    }
}
