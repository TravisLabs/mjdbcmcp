import com.github.gradle.node.npm.task.NpmTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.0.7"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "com.travislabs"
version = "0.1.0-SNAPSHOT"

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("mjdbcmcp")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Official MCP Java SDK. 2.x moved the servlet transports into mcp-core, so there is no
// separate spring-webmvc artifact to pull — the streamable HTTP endpoint is a plain HttpServlet.
val mcpSdkVersion = "2.0.0"

dependencies {
    // Spring Boot BOM via Gradle-native platform support (no io.spring.dependency-management plugin)
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:$mcpSdkVersion"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcClient + HikariCP
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // mcp = mcp-core + the Jackson 3 JSON binding, which lines up with Boot 4's Jackson 3.
    implementation("io.modelcontextprotocol.sdk:mcp")

    // AES-GCM text encryption for stored Datasource passwords (no full Spring Security needed).
    implementation("org.springframework.security:spring-security-crypto")

    // Classification parses agent-written SQL rather than matching its leading keyword (ADR-0004).
    implementation("com.github.jsqlparser:jsqlparser:5.3")

    // Application database.
    runtimeOnly("org.xerial:sqlite-jdbc")

    // Target drivers bundled for the common cases; anything else drops into
    // ${mjdbcmcp.config-dir}/drivers and is picked up by DriverRegistry at startup.
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Second testing tier: real Postgres and MySQL for what only an engine can prove — read-only
    // Connection enforcement, setMaxRows, idle-transaction rollback, DatabaseMetaData shape.
    // H2 is deliberately not used there; its dialect and metadata differ from real engines exactly
    // where this server is most likely to be wrong.
    // Testcontainers 2.x renamed the modules to testcontainers-*; the BOM version comes from Boot's
    // but the platform itself still needs one stated.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-mysql")
}

node {
    version = "24.14.1"
    download = true
    nodeProjectDir = file("frontend")
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    // Skip when the frontend hasn't been installed yet, so the backend still boots standalone.
    onlyIf { file("frontend/src").exists() }
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "build")
    inputs.dir("frontend/src").optional()
    inputs.files("frontend/package.json", "frontend/vite.config.ts", "frontend/index.html", "frontend/tsconfig.json").optional()
    outputs.dir("frontend/dist")
}

tasks.named<Copy>("processResources") {
    dependsOn(npmBuild)
    from("frontend/dist") {
        into("static")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Never touch the developer's real ~/.mjdbcmcp during a test run.
    environment("MJDBCMCP_CONFIG_DIR", layout.buildDirectory.dir("test-config").get().asFile.absolutePath)
}

// Two tiers. `test` is the fast one — Classification and Object Allowlist resolution are pure and
// that is where correctness lives. `integrationTest` is the tier that needs a real engine; it skips
// itself when no container runtime is reachable, so `build` works without one.
tasks.named<Test>("test") {
    exclude("**/*IT.class")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the engine tier against real Postgres and MySQL via Testcontainers."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*IT.class")
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(integrationTest)
}
