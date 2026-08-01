# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, cached until pom.xml itself changes.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests already ran in CI against a real PostgreSQL; repeating them inside the
# image build would double the pipeline for no additional signal.
RUN mvn -B -ntp clean package -DskipTests

# Split the fat jar so dependencies (~90 jars, change rarely) and application
# classes (change every push) land in separate image layers.
#
# Spring Boot 3.3+ `jarmode=tools` produces a *thin* jar: application/<app>.jar
# carries `Main-Class` and a `Class-Path: lib/...` manifest pointing at
# dependencies/lib. No JarLauncher and no exploded loader are involved, so the
# runtime layout below must keep app.jar and lib/ as siblings.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --destination /extracted \
    && mv /extracted/application/*.jar /extracted/application/app.jar

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/  ./

USER app
EXPOSE 8080

# Tuned for a small single-CPU Fly machine: SerialGC and reduced JIT tiering cut
# startup time and resident memory, and the throughput they cost is irrelevant at
# demo traffic. compose.yaml clears this so local runs use a normal JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
