# Multi-stage build for TERIMBERE CSAMS backend
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# Debian JRE (not Alpine): PDF generation needs AWT/fontconfig, which Alpine JRE often lacks.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 1001 csams

USER csams

COPY --from=build /app/target/csams-backend-*.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=production
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
