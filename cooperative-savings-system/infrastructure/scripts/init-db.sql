-- Optional init script for Docker Postgres
-- Flyway owns schema migrations; this only ensures extensions if needed.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
