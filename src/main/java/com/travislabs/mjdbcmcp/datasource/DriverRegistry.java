package com.travislabs.mjdbcmcp.datasource;

import com.travislabs.mjdbcmcp.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads JDBC drivers the operator drops into {@code ${config-dir}/drivers}.
 *
 * <p>{@link DriverManager} ignores drivers loaded by a child classloader, so each one is wrapped in
 * a {@link DriverShim} registered from this class. That also means a URL is matched by
 * {@code acceptsURL} rather than an explicit driver class name — {@code driverClass} on a connection
 * is only honoured when the application classloader can actually see it.
 */
@Component
public class DriverRegistry {

    private static final Logger log = LoggerFactory.getLogger(DriverRegistry.class);

    /** Application configuration properties. */
    private final AppProperties props;
    /** Names of driver classes loaded from the drop-in drivers directory. */
    private final List<String> externalDrivers = new ArrayList<>();

    /**
     * Constructs a DriverRegistry with the given application properties.
     *
     * @param props application properties providing directory paths
     */
    public DriverRegistry(AppProperties props) {
        this.props = props;
    }

    /**
     * Scans the drop-in drivers directory for JAR files and registers discovered JDBC drivers.
     */
    @PostConstruct
    void loadExternalDrivers() {
        Path dir = props.driversDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Cannot create driver directory {}: {}", dir, e.getMessage());
            return;
        }
        List<URL> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                jars.add(p.toUri().toURL());
            }
        } catch (IOException e) {
            log.warn("Cannot scan driver directory {}: {}", dir, e.getMessage());
            return;
        }
        if (jars.isEmpty()) {
            return;
        }
        var loader = new URLClassLoader(jars.toArray(URL[]::new), getClass().getClassLoader());
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            try {
                DriverManager.registerDriver(new DriverShim(driver));
                externalDrivers.add(driver.getClass().getName());
                log.info("Registered external JDBC driver {}", driver.getClass().getName());
            } catch (SQLException e) {
                log.warn("Failed to register driver {}: {}", driver.getClass().getName(), e.getMessage());
            }
        }
    }

    /** Driver class names loaded from the drop-in directory (diagnostics for the admin UI). */
    public List<String> externalDrivers() {
        return List.copyOf(externalDrivers);
    }

    /** All driver class names currently visible to {@link DriverManager}. */
    public List<String> registeredDrivers() {
        return DriverManager.drivers()
                .map(d -> d instanceof DriverShim shim ? shim.delegateClassName() : d.getClass().getName())
                .sorted()
                .toList();
    }

    /**
     * @return the class name if the application classloader can load it, otherwise empty — in which
     *         case the pool must fall back to {@code DriverManager} URL matching.
     */
    public Optional<String> resolvable(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) {
            return Optional.empty();
        }
        try {
            Class.forName(driverClass, false, getClass().getClassLoader());
            return Optional.of(driverClass);
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
