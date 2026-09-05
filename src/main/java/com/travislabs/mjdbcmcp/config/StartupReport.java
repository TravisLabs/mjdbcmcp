package com.travislabs.mjdbcmcp.config;

import com.travislabs.mjdbcmcp.mcp.ToolSurface;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says at startup what the configuration actually permits.
 *
 * <p>ADR-0001 requires this: a Datasource that grants a write Capability while the tool that could
 * use it is disabled looks write-capable in config and is read-only in practice, and that should not
 * be discovered by an agent's failed attempt.
 */
@Component
public class StartupReport {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    /** The Tool Surface for deriving configuration warnings. */
    private final ToolSurface surface;
    /** The running MCP server. */
    private final McpSyncServer mcpServer;
    /** Application configuration properties. */
    private final AppProperties props;

    /**
     * Constructs a StartupReport with required server components and properties.
     *
     * @param surface   the dynamic Tool Surface
     * @param mcpServer the MCP server instance
     * @param props     application configuration properties
     */
    public StartupReport(ToolSurface surface, McpSyncServer mcpServer, AppProperties props) {
        this.surface = surface;
        this.mcpServer = mcpServer;
        this.props = props;
    }

    /**
     * Logs the active MCP endpoint, exposed tools, and any configuration warnings on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.info("MCP endpoint {} exposing tools {}",
                props.mcp().endpoint(),
                mcpServer.listTools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name).toList());
        surface.configurationWarnings().forEach(warning -> log.warn("Configuration: {}", warning));
        if (props.mcp().allowedOrigins().isEmpty() && props.mcp().allowedHosts().isEmpty()) {
            log.info("MCP Origin/Host pinning is off. That is browser DNS-rebinding protection, not "
                    + "access control — this server has no authentication either way (ADR-0003).");
        }
    }
}
