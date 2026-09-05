package com.travislabs.mjdbcmcp.config;

import com.travislabs.mjdbcmcp.mcp.ToolSurface;
import com.travislabs.mjdbcmcp.querylog.QueryLogService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSessionCancellationAdapter;
import java.util.List;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the MCP streamable HTTP endpoint. The SDK's transport is a plain
 * {@link jakarta.servlet.http.HttpServlet}, so it is registered directly rather than through a
 * Spring MVC handler mapping (ADR-0005).
 *
 * <p>Scheduling is enabled here because query-log pruning runs periodically in the background.
 */
@Configuration
@EnableScheduling
public class McpServerConfig {

    /**
     * Configures the Jackson-backed JSON mapper used by the MCP server transport.
     *
     * @return configured {@link McpJsonMapper}
     */
    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    /**
     * Creates and configures the streamable HTTP transport provider for the MCP server.
     *
     * @param json  the JSON mapper for serialization
     * @param props application properties containing endpoint and security settings
     * @return configured {@link HttpServletStreamableServerTransportProvider}
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(McpJsonMapper json, AppProperties props) {
        var builder = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(json)
                .mcpEndpoint(props.mcp().endpoint());
        // Origin/Host pinning is the SDK's DNS-rebinding defence for browsers, not access control.
        // Empty lists opt out deliberately; see ADR-0005.
        var origins = props.mcp().allowedOrigins();
        var hosts = props.mcp().allowedHosts();
        if (!origins.isEmpty() || !hosts.isEmpty()) {
            var validator = DefaultServerTransportSecurityValidator.builder();
            if (!origins.isEmpty()) {
                validator.allowedOrigins(origins);
            }
            if (!hosts.isEmpty()) {
                validator.allowedHosts(hosts);
            }
            builder.securityValidator(validator.build());
        }
        return builder.build();
    }

    /**
     * Registers the MCP streamable transport provider as an asynchronous HTTP servlet.
     *
     * @param transport the MCP transport provider
     * @param props     application properties containing the endpoint path
     * @return a {@link ServletRegistrationBean} for the transport servlet
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport, AppProperties props) {
        var registration = new ServletRegistrationBean<>(transport, props.mcp().endpoint());
        registration.setName("mcpStreamableTransport");
        // The transport streams responses over an async servlet request; without this the container
        // rejects startAsync().
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * Assembles and builds the synchronous MCP server instance with dynamic tools and instructions.
     *
     * @param transport the HTTP transport provider
     * @param surface   the tool surface provider deriving exposed tools
     * @param queryLog  the query log service for tracing executions and handling cancellations
     * @param json      the JSON mapper
     * @param props     application properties
     * @return initialized {@link McpSyncServer}
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transport,
                                       ToolSurface surface,
                                       QueryLogService queryLog,
                                       McpJsonMapper json,
                                       AppProperties props) {
        List<SyncToolSpecification> initial = surface.specifications();
        surface.adopt(initial);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(props.mcp().serverName(), "0.1.0")
                .jsonMapper(json)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions("""
                        Query relational databases over JDBC. Start with list_datasources: each \
                        datasource reports the capabilities it was granted, its enabled tools, and \
                        the schemas and tables it is scoped to. Use list_schemas, list_tables and \
                        describe_table to inspect structure, query for typed reads, raw_query for \
                        read statements, explain_query for execution plans, and raw_execute for \
                        statements or atomic multi-statement scripts that modify data or schema.""")
                .requestTimeout(props.mcp().requestTimeout())
                .validateToolInputs(true)
                .tools(initial)
                .build();

        McpSessionCancellationAdapter.enableCancellationSupport(transport, queryLog);
        return server;
    }
}
