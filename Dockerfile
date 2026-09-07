# Multi-stage build for optimized Docker image
FROM maven:3.9.8-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (cache layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and scripts
COPY src ./src
COPY scripts ./scripts
# Build (assets will be minified locally before Docker build, or use source files)
# Minification happens in CI/CD pipeline or locally before building Docker image
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17.0.13_11-jre

WORKDIR /app

# Install wget for health checks
RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring

# Copy the JAR from build stage
# Glob, not a pinned name, so a version bump cannot silently break the COPY.
COPY --from=build /app/target/scrum-ceremonies-*.jar app.jar

# Change ownership to non-root user
RUN chown spring:spring app.jar

USER spring:spring

# Expose port (configurable via PORT env var)
EXPOSE 8080

# Container health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application with JVM flags optimized for faster startup
# -XX:+UseSerialGC: Faster startup, lower memory overhead (good for containers)
# -XX:TieredStopAtLevel=1: Disable C2 compiler, faster startup
# -Djava.security.egd=file:/dev/./urandom: Faster secure random (non-blocking)
#
# -XX:MaxRAMPercentage=65.0 sets the heap explicitly rather than leaving it at the JVM's
# default of 25% of container memory. This app's live heap plus metaspace regularly runs
# well past a 25%-of-1GiB (256 MB) budget, which leaves no room for GC headroom and risks
# thrashing or OOM on small containers. 65% of a 1 GiB container is ~665 MB of heap, with
# the rest left for metaspace, code cache, thread stacks and direct buffers. Re-measure
# with `jcmd <pid> GC.heap_info` against a real instance if you change the container's
# memory limit.
#
# -Xverify:none is retained deliberately. It is deprecated since JDK 13 and prints a
# warning, but it is not a no-op: it disables remote bytecode verification (confirmed via
# -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal), which is a real startup-time cost
# to skip for a JAR we built ourselves and did not download from anywhere untrusted.
# Removing it would re-enable that verification, not merely silence a warning. The actual
# liability is forward-compatibility — a future JDK may remove the flag outright — so this
# should be revisited when that happens, not before.
ENTRYPOINT ["java", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:MaxRAMPercentage=65.0", \
    "-Xverify:none", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

