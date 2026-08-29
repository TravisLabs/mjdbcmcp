package com.travislabs.mjdbcmcp;

import com.travislabs.mjdbcmcp.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class MjdbcmcpApplication {

    public static void main(String[] args) {
        // SQLite will not create the parent directory of its database file, and the datasource is
        // built before any bean of ours runs — so the config dir has to exist before the context does.
        createConfigDir();
        SpringApplication.run(MjdbcmcpApplication.class, args);
    }

    private static void createConfigDir() {
        String configured = System.getenv("MJDBCMCP_CONFIG_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("user.home") + "/.mjdbcmcp";
        }
        try {
            Files.createDirectories(Path.of(configured));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create config dir " + configured, e);
        }
    }
}
