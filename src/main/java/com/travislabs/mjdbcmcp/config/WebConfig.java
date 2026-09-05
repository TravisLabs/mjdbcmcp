package com.travislabs.mjdbcmcp.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app out of the JAR and hands unknown paths back to {@code index.html} so
 * the router owns client-side routes. {@code /api}, {@code /mcp} and {@code /actuator} are handled
 * before this ever runs.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Registers static resource handlers to serve the built React UI assets from {@code classpath:/static/}
     * and fallback to {@code index.html} for client-side single-page application routing.
     *
     * @param registry Spring MVC resource handler registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
