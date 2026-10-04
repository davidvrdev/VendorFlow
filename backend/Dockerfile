# syntax=docker/dockerfile:1
# ---- build stage: JDK + Maven Wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Resolve dependencies in their own layer so source changes do not re-download them.
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/vendorflow-api.jar extract --layers --destination target/extracted

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
# Layer order = change frequency (dependencies rarely change, application code every build).
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./
USER app
# No secrets here: DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD etc. are injected at runtime.
# Container-aware heap (75% of the memory limit leaves room for metaspace, threads and the TLS/S3 buffers),
# die on OOM so the platform restarts us instead of leaving a half-dead JVM, no stack-trace-less fast throws.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
# Readiness probe (application state). Generous start period: Flyway migrations run on startup.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
# exec so the JVM is PID 1 and receives SIGTERM (graceful shutdown).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar vendorflow-api.jar"]
