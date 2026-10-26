# Staging image: backend + built frontend in one container, with an embedded PostgreSQL when no DATABASE_URL is
# given (free hosting). Demo data is seeded on first start. Not for production.
FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
ENV VITE_API_BASE_URL=/api/v1 VITE_APP_NAME="OuWealth Community (staging)" VITE_APP_ENV=staging VITE_ENABLE_PWA=false
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS api
WORKDIR /app
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql postgresql-contrib fontconfig fonts-dejavu-core python3 python3-openpyxl curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=api /app/target/csams-backend-*.jar /app/app.jar
COPY --from=web /web/dist /app/web
COPY staging/entrypoint.sh staging/seed.py /app/
RUN chmod +x /app/entrypoint.sh && mkdir -p /app/uploads /var/run/postgresql && chown -R postgres:postgres /var/run/postgresql
ENV SPRING_PROFILES_ACTIVE=staging \
    APP_SPA_ENABLED=true \
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/web/ \
    APP_SEED_DEFAULT_ADMIN=true \
    FILE_STORAGE_BASE_PATH=/app/uploads \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Xss512k -XX:+UseSerialGC -XX:MaxRAM=400m -XX:MaxRAMPercentage=70 -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
