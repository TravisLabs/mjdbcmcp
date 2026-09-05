package io.modelcontextprotocol.spec;

import com.travislabs.mjdbcmcp.querylog.QueryLogService;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import java.lang.reflect.Field;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Registers the standard {@code notifications/cancelled} handler on streamable server session factories
 * so client aborts/cancellations are handled cleanly and propagate to the active query log.
 */
public final class McpSessionCancellationAdapter {

    /** JSON-RPC method name for MCP cancellation notifications. */
    public static final String METHOD_NOTIFICATION_CANCELLED = "notifications/cancelled";
    private static final Logger log = LoggerFactory.getLogger(McpSessionCancellationAdapter.class);

    private McpSessionCancellationAdapter() {
    }

    /**
     * Reflectively accesses the session factory on the transport provider and attaches cancellation support.
     *
     * @param transport streamable HTTP transport provider
     * @param queryLog  query log service for routing cancellations
     */
    public static void enableCancellationSupport(
            HttpServletStreamableServerTransportProvider transport,
            QueryLogService queryLog) {
        try {
            Field field = HttpServletStreamableServerTransportProvider.class.getDeclaredField("sessionFactory");
            field.setAccessible(true);
            McpStreamableServerSession.Factory current = (McpStreamableServerSession.Factory) field.get(transport);
            if (current != null) {
                withCancellationSupport(current, queryLog);
            }
        } catch (Exception e) {
            log.warn("Could not attach cancellation support to MCP transport session factory: {}", e.getMessage());
        }
    }

    /**
     * Registers the {@code notifications/cancelled} handler on a session factory instance.
     *
     * @param factory  server session factory
     * @param queryLog query log service
     * @return the configured session factory
     */
    public static McpStreamableServerSession.Factory withCancellationSupport(
            McpStreamableServerSession.Factory factory,
            QueryLogService queryLog) {
        if (factory instanceof DefaultMcpStreamableServerSessionFactory defaultFactory) {
            defaultFactory.notificationHandlers.put(METHOD_NOTIFICATION_CANCELLED, (exchange, params) -> {
                try {
                    String reason = "Operation was aborted";
                    Object requestId = null;
                    if (params instanceof Map<?, ?> map) {
                        requestId = map.get("requestId");
                        if (map.get("reason") != null) {
                            reason = String.valueOf(map.get("reason"));
                        }
                    }
                    log.debug("Client cancelled request {} on session {}: {}", requestId, exchange.sessionId(), reason);
                    queryLog.cancelSession(exchange.sessionId(), requestId, reason);
                } catch (Exception e) {
                    log.warn("Error handling cancellation notification: {}", e.getMessage(), e);
                }
                return Mono.empty();
            });
        }
        return factory;
    }
}
