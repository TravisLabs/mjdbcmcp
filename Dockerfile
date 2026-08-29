# ── Build stage ──────────────────────────────────────────────────────────────
# Node is not installed here: the Gradle node plugin downloads its own toolchain
# (build.gradle.kts `node { download = true }`), so one source of truth decides
# the Node version for local builds and image builds alike.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Warm the Gradle dependency cache before the source lands, so an edit to a Java
# file doesn't re-resolve every dependency. Best-effort: a failure here only
# costs cache, never correctness.
COPY gradle/ gradle/
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Same idea for the frontend: npmInstall pulls the Node toolchain and node_modules
# from the manifests alone, so editing a .tsx doesn't re-install the world.
COPY frontend/package.json frontend/package-lock.json frontend/
RUN ./gradlew npmInstall --no-daemon -q 2>/dev/null || true

# Build everything (Vite build + bootJar)
COPY . .
RUN ./gradlew bootJar --no-daemon -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# gosu drops to the operator's PUID/PGID at start so the config volume isn't
# written as root; curl serves the healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gosu \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# SQLite application database, the password encryption key, and the drop-in
# driver directory all live here. Losing this volume means re-entering every
# connection — including the passwords, which cannot be decrypted without the key.
VOLUME /mjdbcmcp_config

ENV MJDBCMCP_CONFIG_DIR=/mjdbcmcp_config \
    SERVER_PORT=8080 \
    # The application defaults to loopback, which inside a container means
    # "unreachable". Published ports are the operator's access control here.
    SERVER_ADDRESS=0.0.0.0 \
    # Empty = the transport's Origin/Host pinning is off. The shipped defaults
    # name localhost:8080, which would reject every request once the service is
    # behind a proxy or a published port on another host. Set both to your real
    # public origin and host (comma-separated) when a browser can reach this.
    MJDBCMCP_MCP_ALLOWEDORIGINS="" \
    MJDBCMCP_MCP_ALLOWEDHOSTS="" \
    JAVA_OPTS="" \
    PUID=1000 \
    PGID=1000

EXPOSE ${SERVER_PORT}

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["/entrypoint.sh"]
