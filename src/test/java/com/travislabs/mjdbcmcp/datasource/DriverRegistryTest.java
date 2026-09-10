package com.travislabs.mjdbcmcp.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.travislabs.mjdbcmcp.config.AppProperties;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriverRegistryTest {

    @Test
    void builtInDriversDoNotDuplicateWhenEmpty(@TempDir Path tempDir) {
        var props = new AppProperties(tempDir, null, null);
        var registry = new DriverRegistry(props);
        registry.loadExternalDrivers();

        List<String> registered = registry.registeredDrivers();
        assertThat(registered).contains("org.postgresql.Driver", "org.sqlite.JDBC", "org.h2.Driver");
        assertThat(registered).doesNotHaveDuplicates();
        assertThat(registry.externalDrivers()).isEmpty();
    }

    @Test
    void externalDriversDoNotReRegisterBuiltInDrivers(@TempDir Path tempDir) throws Exception {
        Path driversDir = tempDir.resolve("drivers");
        Files.createDirectories(driversDir);

        // Compile a dummy JDBC driver class outside the test classpath
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("test/driver"));
        Path javaFile = srcDir.resolve("test/driver/FakeExternalDriver.java");
        String driverSource = """
                package test.driver;
                import java.sql.*;
                import java.util.Properties;
                import java.util.logging.Logger;

                public class FakeExternalDriver implements Driver {
                    public Connection connect(String url, Properties info) { return null; }
                    public boolean acceptsURL(String url) { return url.startsWith("jdbc:fake:"); }
                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public Logger getParentLogger() { return null; }
                }
                """;
        Files.writeString(javaFile, driverSource, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        int result = compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString());
        assertThat(result).isZero();

        // Package into a JAR with META-INF/services/java.sql.Driver
        Path jarFile = driversDir.resolve("fake-driver.jar");
        try (var jos = new JarOutputStream(new FileOutputStream(jarFile.toFile()))) {
            // Add .class file
            Path classFile = classesDir.resolve("test/driver/FakeExternalDriver.class");
            jos.putNextEntry(new JarEntry("test/driver/FakeExternalDriver.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();

            // Add SPI file
            jos.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            jos.write("test.driver.FakeExternalDriver\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        var props = new AppProperties(tempDir, null, null);
        var registry = new DriverRegistry(props);
        registry.loadExternalDrivers();

        // Only the external driver should be recorded as external
        assertThat(registry.externalDrivers()).containsExactly("test.driver.FakeExternalDriver");

        // Registered drivers should include the external driver and built-ins with no duplicates
        List<String> registered = registry.registeredDrivers();
        assertThat(registered).contains("test.driver.FakeExternalDriver", "org.postgresql.Driver", "org.sqlite.JDBC");
        assertThat(registered).doesNotHaveDuplicates();
    }
}
