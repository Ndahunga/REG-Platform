# Deployment overview

The same codebase runs locally and in production. Do not hardcode secrets or provider-specific lock-in.

## Build artifacts

```bash
# Backend JAR
cd backend && mvn -DskipTests package
# Artifact: target/csams-backend-1.0.0-SNAPSHOT.jar

# Frontend static assets
cd frontend && npm ci && npm run build
# Artifact: dist/
```

## Runtime requirements

- JDK 21
- PostgreSQL 16+
- Reverse proxy (Nginx, Caddy, Traefik, or cloud load balancer) terminating HTTPS
- Environment variables from `.env.example` (production values via secrets manager)
- Writable uploads volume (or S3-compatible object storage via `FILE_STORAGE_TYPE`)

## Suggested topology

```
[Browser / future PWA] → [HTTPS reverse proxy] → [Frontend static]
                                              → [Backend :8080]
                                              → [PostgreSQL]
                                              → [Object storage / local uploads]
```

## Compatible hosts

VPS, AWS, Azure, Google Cloud, Render, Railway, DigitalOcean — choose based on client preference. Use `docker-compose.yml` for local integration; production may use Compose (`docker-compose.prod.example.yml`), Kubernetes, or managed services.

## OuWealth subscription payments

See [Subscription payments (sandbox & production)](./subscription-payments.md) for MTN MoMo and Flutterwave environment variables, callback/webhook URLs, reconciliation, and the go-live checklist.

## Production checklist

Complete before go-live:

1. **HTTPS reverse proxy** — terminate TLS at the edge; forward `X-Forwarded-Proto` / `X-Forwarded-For` to the backend.
2. **Env secrets** — set strong unique `JWT_ACCESS_SECRET` and `JWT_REFRESH_SECRET` (≥64 chars); never commit `.env`. Prefer a vault / host secrets.
3. **Flyway on deploy** — migrations run on application startup. Take a DB backup before each release that includes schema changes.
4. **Refresh cookie Secure** — `application-production.yml` must keep `app.jwt.refresh-cookie-secure: true` (and SameSite appropriate for your domain layout).
5. **CORS origins** — set `CORS_ALLOWED_ORIGINS` to the exact production frontend origin(s), no wildcards in production.
6. **File storage** — configure `FILE_STORAGE_TYPE` / path or S3 credentials; ensure the volume/bucket is backed up with the database.
7. **Health probes** — wire liveness to `/actuator/health/liveness` and readiness to `/actuator/health/readiness` (or documented equivalents).
8. **HSTS** — enable Strict-Transport-Security on the reverse proxy for HTTPS sites (do not force HSTS on local HTTP).
9. **Uploads permissions** — backend process user can write the storage path; no world-writable dirs.
10. **Admin bootstrap** — confirm initial SUPER_ADMIN credentials were rotated from any temporary seed.

## Health checks

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`
- Public: `/api/v1/public/health`
- Authenticated info: `/api/v1/system/info`

## Migrations

Flyway runs on application startup from `backend/src/main/resources/db/migration/`. Backup the database before deploying schema changes. See [Backup and recovery](../backups/backup-and-recovery.md).

## Example Compose

See [`docker-compose.prod.example.yml`](../../docker-compose.prod.example.yml) at the repository root — an illustrative stack that references `.env` and contains no real secrets.
