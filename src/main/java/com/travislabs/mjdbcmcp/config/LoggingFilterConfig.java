package com.travislabs.mjdbcmcp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.context.annotation.Configuration;

/**
 * Suppresses benign disconnect and async completion errors from the MCP transport layer
 * when a client aborts a request and closes its HTTP socket before the response is flushed.
 */
@Configuration(proxyBeanMethods = false)
public class LoggingFilterConfig {

    static {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.addTurboFilter(new TurboFilter() {
                @Override
                public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
                    if (logger != null && logger.getName().contains("McpTransport")) {
                        if (format != null && (format.contains("Failed to send message") || format.contains("Failed to complete async context"))) {
                            if (params != null) {
                                for (Object param : params) {
                                    if (param instanceof String s && (s.contains("Client disconnected") || s.contains("MUST_COMPLETE"))) {
                                        return FilterReply.DENY;
                                    }
                                }
                            }
                            if (t != null && t.getMessage() != null && (t.getMessage().contains("Client disconnected") || t.getMessage().contains("MUST_COMPLETE"))) {
                                return FilterReply.DENY;
                            }
                        }
                    }
                    return FilterReply.NEUTRAL;
                }
            });
        }
    }
}
