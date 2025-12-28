# Config Service Dockerfile
FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

# Copy only pom files first for better caching
COPY pom.xml .
COPY common/pom.xml common/
COPY waybill-service/pom.xml waybill-service/
COPY payment-service/pom.xml payment-service/
COPY config-service/pom.xml config-service/

# Download dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -pl config-service -am -B -q || \
    mvn dependency:go-offline -pl config-service -am -B -q || \
    mvn dependency:go-offline -pl config-service -am -B -q

# Copy source code
COPY common/src common/src
COPY config-service/src config-service/src

# Build with retries for transient network issues
RUN mvn -B -DskipTests -pl config-service -am package || \
    mvn -B -DskipTests -pl config-service -am package

FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Tasty ERP Team"

RUN apk add --no-cache curl

RUN addgroup -S tasty && adduser -S tasty -G tasty
USER tasty

WORKDIR /app

COPY --from=build /workspace/config-service/target/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8888

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
