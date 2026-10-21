# Architecture overview — TERIMBERE CSAMS

## Style

Modular monolith (single deployable Spring Boot JAR + React SPA). No microservices in the first production release.

## Backend modules

Authentication · Users · Roles · Cooperatives · Membership · Members · Contributions · Special contributions · Loans · Repayments · Fines · Fine payments · Social fund · Investments · Income/expenses · Ledger · Payouts · Reports · Notifications · Files · Settings · Audit · Backups · System health

Each module follows: `controller` → `service` → `repository` / `entity` / `dto` / `mapper` / `validation`.

Shared packages: `security`, `configuration`, `exceptions`, `utilities`, `auditing`, `pagination`, `file storage`, `financial calculations`.

## Frontend

React + TypeScript + Vite + MUI, feature-oriented folders, Redux Toolkit for session/UI state, TanStack Query for server state, React Hook Form + Yup for forms, i18next for localization.

Prepared for Phase 12 PWA (manifest, service worker, install prompt) without offline financial submissions.

## Data

PostgreSQL with Flyway migrations, UUID keys, optimistic locking, soft-delete where appropriate, NUMERIC money columns.

## Multi-cooperative isolation

Every financial record carries a cooperative identifier. APIs authorize by user membership and role before returning or mutating data.
