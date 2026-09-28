# Multi-stage build: Java 11 + Spring Boot 2.6.3 (M0)
FROM maven:3.8-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package \
    && cp target/heatnet-tracing-service-*.jar /build/app.jar

FROM eclipse-temurin:11-jre-focal
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=docker
ENV HEATNET_CONFIG_DIR=/app/config
ENV HEATNET_DATA_DIR=/app/data
ENV JAVA_OPTS="-Xms512m -Xmx6g"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=8 --start-period=90s \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
