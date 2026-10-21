# Monitoring overview

Operational visibility for TERIMBERE CSAMS without locking the stack to a single vendor.

## Health endpoints

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `/api/v1/public/health` | Public | Lightweight reachability for reverse proxies and smoke checks |
| `/actuator/health` | Depends on config | Aggregate Spring Boot health |
| `/actuator/health/liveness` | Probe | Process is up — use for container liveness |
| `/actuator/health/readiness` | Probe | Ready to serve traffic (DB / deps) — use for readiness |
| `/api/v1/system/info` | Authenticated | App name, active profiles, timestamp, DB reachable, optional Flyway/Java details |

**Probe guidance**

- Liveness: fail only when the JVM process should be restarted.
- Readiness: fail when PostgreSQL (or other hard deps) are unavailable.
- Prefer readiness for load-balancer target health so traffic drains during migrations/outages.

## Structured logging pattern

Prefer one JSON-friendly line per event with stable keys:

```text
timestamp level logger requestId cooperativeId userId action message
```

Recommended MDC / field names:

- `requestId` — correlate with `X-Request-Id` from the API client
- `userId` / `cooperativeId` — when available in the security context
- `action` — short verb (`LOGIN_FAILED`, `LOAN_DISBURSE`, `AUDIT_READ`)

Use INFO for business milestones, WARN for recoverable abuse (rate limit), ERROR for unexpected failures with stack traces once.

## What not to log

Never write to application logs:

- Passwords, refresh tokens, JWT access tokens, or password-reset tokens
- Full card / bank account numbers (none expected today — keep the rule)
- National ID or other PII beyond what ops need for support
- Entire request/response bodies that may contain financial member lists
- Secrets from `.env` / environment variables

Mask or omit sensitive query parameters. Audit detail belongs in `audit_logs`, not free-form ERROR dumps of previous/new JSON at INFO level.

## Future Prometheus / OpenTelemetry hook points

The codebase is ready for opt-in telemetry without redesign:

1. **Actuator metrics** — enable `micrometer-registry-prometheus` and expose `/actuator/prometheus` behind the admin network only.
2. **HTTP server metrics** — Spring Boot auto-instruments request timers; tag by `uri` carefully (avoid high-cardinality path IDs).
3. **DB pool** — HikariCP metrics via Micrometer (`hikaricp.*`).
4. **Business counters** — increment on notification publish, payout confirm, failed login lock (custom `MeterRegistry` beans).
5. **OpenTelemetry** — attach the Java agent or Micrometer Tracing bridge; propagate `traceparent` / `X-Request-Id` through Nginx → backend → logs.
6. **Frontend** — optional later: Web Vitals to a privacy-respecting endpoint (Phase 12+).

Until those are enabled, rely on health probes, structured logs, and the authenticated System Health page.
