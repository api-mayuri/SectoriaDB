# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Download dependencies first (cacheable layer); sibling modules are not built yet, so skip org.example
COPY pom.xml .
COPY sectoriadb-metastore/pom.xml sectoriadb-metastore/
COPY sectoriadb-engine/pom.xml sectoriadb-engine/
COPY sectoriadb-core/pom.xml sectoriadb-core/
COPY sectoriadb-server/pom.xml sectoriadb-server/
RUN mvn -B dependency:go-offline -q -DexcludeGroupIds=org.example

# Build the jar
COPY sectoriadb-metastore/src/ sectoriadb-metastore/src/
COPY sectoriadb-engine/src/ sectoriadb-engine/src/
COPY sectoriadb-core/src/ sectoriadb-core/src/
COPY sectoriadb-server/src/ sectoriadb-server/src/
RUN mvn -B package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Unprivileged user; /data is what the volume below mounts over (a fresh named volume inherits this ownership)
RUN addgroup -S sectoria && adduser -S -G sectoria sectoria \
 && mkdir -p /app /data/meta /data/storage \
 && chown -R sectoria:sectoria /app /data

WORKDIR /app
# The version is not hard-coded: the repackaged jar is the only *.jar of the target directory
COPY --from=builder --chown=sectoria:sectoria /build/sectoriadb-server/target/SectoriaDB-*.jar /app/sectoriadb.jar

# One volume for everything the server writes (/data/meta, /data/storage incl. the upload spool /data/storage/.tmp):
# a snapshot of this single volume is consistent across metadata and blobs.
VOLUME ["/data"]

# ── Configuration via environment variables ───────────────────────────────────
# Spring's relaxed binding maps SECTORIADB_* / SERVER_PORT / SPRING_* to the properties of application.properties, so
# `docker compose exec ... java -jar` (which bypasses ENTRYPOINT) sees the same configuration.
ENV SECTORIADB_META_DIR="/data/meta"
ENV SECTORIADB_DATA_DIR="/data/storage"
ENV SECTORIADB_S3_AUTH_ENABLED="true"
ENV SECTORIADB_S3_REGION="us-east-1"
ENV SERVER_PORT="8080"
ENV MANAGEMENT_SERVER_PORT="9464"
# Heap = 70 % of the container memory limit (the host memory without a limit). Resident cuckoo tables take about
# 9 MiB each (sectoriadb.cache.max-resident-tables, default 64 = ~576 MiB): give the container at least 1.5 GiB or lower
# SECTORIADB_CACHE_MAX_RESIDENT_TABLES. An OutOfMemoryError ends the process so the restart policy can bring it back clean.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"
ENV SPRING_SHELL_INTERACTIVE_ENABLED="false"

USER sectoria

# 8080: S3 API; 9464: management port (/actuator/health, /actuator/prometheus; not SigV4-protected)
EXPOSE 8080 9464

# busybox wget (part of the alpine base image, no package needed) against the management port: the S3 port answers 403
# to anonymous requests. wget exits non-zero on 503, i.e. when the health status is DOWN.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -T 4 -O /dev/null "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-9464}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/sectoriadb.jar"]
