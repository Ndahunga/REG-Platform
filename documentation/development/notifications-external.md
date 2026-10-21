# External notifications (email / SMS / push)

In-app notifications are production-ready (`notifications` table + UI).

Email is delivered by the existing `EmailNotificationPublisher` channel
(`NotificationFacade` → channels). Do not add a second mailer.

## Email

SMTP uses Spring Mail and these environment variables:

- `MAIL_HOST` — required to enable sending
- `MAIL_PORT` — default `587`
- `MAIL_USERNAME` / `MAIL_PASSWORD` — optional depending on the SMTP server
- `MAIL_FROM` — required to enable sending
- `MAIL_ENABLED` — default `true`; set `false` to force-skip
- `MAIL_CONNECTION_TIMEOUT_MS` / `MAIL_TIMEOUT_MS` / `MAIL_WRITE_TIMEOUT_MS` — default `10000`

When host or from is blank, the email channel logs and skips. Missing mail
credentials must not prevent application startup. Mail actuator health is off
by default (`MAIL_HEALTH_ENABLED=false`) so an unused SMTP host cannot fail
readiness.

Port 587 uses STARTTLS (`MAIL_SMTP_STARTTLS`, default true). The visible From
name is `OuWealth Community`; the address is always `MAIL_FROM` (never hardcoded).

Delivery is scheduled **after a successful database commit**, then sent
asynchronously (same pattern as WhatsApp). SMTP failure is logged and never
rolls back loans, contributions, or other business work. The in-app row stays.

Recipient locale is not stored on `User`. Transactional emails use the existing
English notification title/body.

## Known issue (not implemented here)

Password reset still tells the client a reset link was sent, but this change
does not add password-reset email. That remains a separate follow-up.

SMS and browser push are not implemented.
