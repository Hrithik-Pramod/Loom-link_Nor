# ═══════════════════════════════════════════════════════════════════════════════
# LOOM LINK EDGE — Multi-Stage Production Dockerfile
# Nordic Energy Matchbox 2026 | Sovereign Intelligence Layer
#
# Build:   docker build -t loom-link-edge:0.1.0 .
# Run:     docker run -p 8080:8080 --env-file .env loom-link-edge:0.1.0
# Compose: docker compose up -d
# ═══════════════════════════════════════════════════════════════════════════════

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Cache Maven dependencies (layer caching optimization)
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B 2>/dev/null || true

# If no Maven wrapper, install Maven
RUN apk add --no-cache maven || true

# Copy source and build
COPY src src
RUN mvn clean package -DskipTests -B -q \
    || ./mvnw clean package -DskipTests -B -q

# ── Stage 2: Runtime ───────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user for production
RUN addgroup -S loomlink && adduser -S loomlink -G loomlink

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /build/target/loom-link-edge-*.jar app.jar

# Copy the startup banner
COPY --from=builder /build/src/main/resources/banner.txt /app/banner.txt

# JVM tuning for HM90 Ryzen 9 (constrained memory in container)
ENV JAVA_OPTS="-Xms512m -Xmx2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom"

# Expose Spring Boot default port and Actuator
EXPOSE 8080

# Health check using Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Run as non-root
USER loomlink

# Entrypoint with JVM opts
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
