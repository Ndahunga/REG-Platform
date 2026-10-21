# TERIMBERE — Cooperative Savings Account Management System (CSAMS)

Production-oriented web application for managing cooperative savings groups: members, contributions, loans, fines, social fund, investments, payouts, reports, and audit trails.

**Brand:** TERIMBERE  
**Stack:** React + TypeScript (Vite) · Spring Boot 3 · PostgreSQL · JWT

---

## Repository layout

```
cooperative-savings-system/
├── frontend/           # React + MUI + TanStack Query + Redux Toolkit
├── backend/            # Spring Boot modular monolith
├── infrastructure/     # Nginx, SQL init, scripts
├── uploads/            # Local file storage (dev)
├── documentation/      # Architecture, deployment, backups
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java JDK | 21+ |
| Maven | 3.9+ (or use portable Maven / wrapper) |
| Node.js | 20+ (22/24 OK) |
| PostgreSQL | 16+ |
| Docker | Optional (Compose stack) |

---

## Local database setup

1. Ensure PostgreSQL is running on `localhost:5432`.
2. Create role and database (example):

```sql
CREATE ROLE csams_user LOGIN PASSWORD 'your_strong_password';
CREATE DATABASE cooperative_savings_db OWNER csams_user;
\c cooperative_savings_db
GRANT ALL ON SCHEMA public TO csams_user;
ALTER SCHEMA public OWNER TO csams_user;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

3. Copy environment template:

```bash
cp .env.example .env
```

Edit `.env` with your PostgreSQL password and JWT secrets. **Never commit `.env`.**

---

## Backend (http://localhost:8080)

```bash
cd backend
# Load env vars from ../.env in your shell, or set them in the IDE run config
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Useful endpoints after startup:

| Endpoint | Purpose |
|----------|---------|
| http://localhost:8080/swagger-ui.html | OpenAPI UI |
| http://localhost:8080/api-docs | OpenAPI JSON |
| http://localhost:8080/actuator/health | Health |
| http://localhost:8080/api/v1/public/health | Public health |
| http://localhost:8080/actuator/health/liveness | Liveness |
| http://localhost:8080/actuator/health/readiness | Readiness |

Flyway applies migrations from `backend/src/main/resources/db/migration/` on startup.

```bash
mvn test
mvn -DskipTests package
```

---

## Frontend (http://localhost:5173)

```bash
cd frontend
cp .env.example .env.local   # if needed
npm install
npm run dev
```

```bash
npm test
npm run build
```

---

## Docker Compose (optional)

When Docker is available:

```bash
cp .env.example .env
docker compose up -d --build
```

---

## Environments

| Profile | Backend config | Frontend env |
|---------|----------------|--------------|
| Local | `application-local.yml` | `.env.local` |
| Test | `application-test.yml` | — |
| Staging | `application-staging.yml` | `.env.staging` |
| Production | `application-production.yml` | `.env.production` |

---

## Implementation phases

| Phase | Scope | Status |
|-------|--------|--------|
| 1 | Foundation (structure, config, Flyway, Swagger, UI shell, PWA prep) | **Complete** |
| 2 | Authentication & authorization (JWT, refresh cookies, RBAC) | **Complete** |
| 3 | Cooperatives & members | **Complete** |
| 4 | Contributions | **Complete** |
| 5 | Loans | **Complete** |
| 6 | Fines | **Complete** |
| 7 | Social fund | **Complete** |
| 8 | Investments, income/expenses, ledger | **Complete** |
| 9 | Payouts | **Complete** |
| 10 | Reports & Excel imports | **Complete** |
| 11 | Production quality (audit UI, notifications, settings, monitoring, Docker hardening) | **Complete** |
| 12 | PWA installability | **Complete** |

---

## Security notes

- Passwords hashed with BCrypt (Phase 2 login wiring).
- Access tokens kept in memory on the frontend (not `localStorage`).
- Refresh tokens intended for HTTP-only cookies.
- Financial amounts use `BigDecimal` / `NUMERIC` — never `float`/`double`.
- Cooperative data isolation enforced on the backend.
- Approved ledger entries are immutable; corrections use reversals.

---

## Documentation

- [Architecture](documentation/architecture/overview.md)
- [Development](documentation/development/getting-started.md)
- [Deployment](documentation/deployment/overview.md)
- [Monitoring](documentation/monitoring/overview.md)
- [Backups](documentation/backups/backup-and-recovery.md)
- Production Compose example: [`docker-compose.prod.example.yml`](docker-compose.prod.example.yml)

---

## License / ownership

Client project for TERIMBERE cooperative savings operations. Not an academic prototype.
