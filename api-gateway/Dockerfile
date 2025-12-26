# API Gateway Dockerfile
FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .
RUN mvn -q -DskipTests -pl api-gateway -am package

FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Tasty ERP Team"

RUN apk add --no-cache curl

RUN addgroup -S tasty && adduser -S tasty -G tasty
USER tasty

WORKDIR /app

COPY --from=build /workspace/api-gateway/target/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 3005

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
