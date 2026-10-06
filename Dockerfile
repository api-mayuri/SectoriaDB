# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Download dependencies first (cacheable layer); sibling modules are not built yet, so skip org.example
COPY pom.xml .
COPY sectoriadb-core/pom.xml sectoriadb-core/
COPY sectoriadb-server/pom.xml sectoriadb-server/
RUN mvn -B dependency:go-offline -q -DexcludeGroupIds=org.example

# Build the jar
COPY sectoriadb-core/src/ sectoriadb-core/src/
COPY sectoriadb-server/src/ sectoriadb-server/src/
RUN mvn -B package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app
COPY --from=builder /build/sectoriadb-server/target/SectoriaDB-1.0-SNAPSHOT.jar sectoriadb.jar

# Persistent storage directories
RUN mkdir -p /data/meta /data/storage
VOLUME ["/data/meta", "/data/storage"]

# ── Configuration via environment variables ───────────────────────────────────
ENV SECTORIADB_META_DIR="/data/meta"
ENV SECTORIADB_DATA_DIR="/data/storage"
ENV SECTORIADB_S3_AUTH_ENABLED="true"
ENV SECTORIADB_S3_REGION="us-east-1"
ENV SECTORIADB_S3_CREDENTIALS=""
ENV SERVER_PORT="8080"
ENV JAVA_OPTS="-Xmx512m -XX:+UseG1GC"
ENV SPRING_SHELL_INTERACTIVE_ENABLED="false"

EXPOSE 8080

#HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
#  CMD curl -sf http://localhost:${SERVER_PORT}/ || exit 1

ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS \
    -Dsectoriadb.meta-dir=${SECTORIADB_META_DIR} \
    -Dsectoriadb.data-dir=${SECTORIADB_DATA_DIR} \
    -Dsectoriadb.s3.auth.enabled=${SECTORIADB_S3_AUTH_ENABLED} \
    -Dsectoriadb.s3.region=${SECTORIADB_S3_REGION} \
    -Dserver.port=${SERVER_PORT} \
    -Dspring.shell.interactive.enabled=${SPRING_SHELL_INTERACTIVE_ENABLED} \
    -jar /app/sectoriadb.jar"]
