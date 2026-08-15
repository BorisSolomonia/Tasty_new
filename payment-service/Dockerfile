# Payment Service Dockerfile
FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

# Copy only pom files first for better caching
COPY pom.xml .
COPY common/pom.xml common/
COPY waybill-service/pom.xml waybill-service/
COPY payment-service/pom.xml payment-service/
COPY config-service/pom.xml config-service/

# Download dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -pl payment-service -am -B -q || \
    mvn dependency:go-offline -pl payment-service -am -B -q || \
    mvn dependency:go-offline -pl payment-service -am -B -q

# Copy source code
COPY common/src common/src
COPY payment-service/src payment-service/src

# Build with retries for transient network issues
RUN mvn -B -DskipTests -pl payment-service -am package || \
    mvn -B -DskipTests -pl payment-service -am package

FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Tasty ERP Team"

RUN apk add --no-cache curl

RUN addgroup -S tasty && adduser -S tasty -G tasty
USER tasty

WORKDIR /app

COPY --from=build /workspace/payment-service/target/*.jar app.jar

# JVM sizing (BOR-90). Measured on this service, idle after warm-up: ~200 MB of
# non-heap (Metaspace, GC tables, code cache, CDS, symbols, threads) is committed
# BEFORE any heap is used, and JIT arenas peak higher during start-up. With
# MaxRAMPercentage=75 the heap alone could reach 75% of the cgroup limit, so a
# full heap meant RSS > limit and a silent `exit 137` OOM-kill (no log, no dump).
#  - MaxRAMPercentage lowered so heap + measured non-heap fits the limit;
#  - explicit Metaspace/direct-memory caps so a leak there is a logged
#    java.lang.OutOfMemoryError, not a cgroup kill;
#  - ExitOnOutOfMemoryError so `restart: unless-stopped` restarts a JVM that
#    has genuinely run out instead of leaving a half-dead one serving 500s.
# Override JAVA_OPTS via the compose `environment:` block if the limit changes.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
