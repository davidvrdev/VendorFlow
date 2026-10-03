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
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./
USER app
# No secrets here: DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD are injected at runtime.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar vendorflow-api.jar"]
