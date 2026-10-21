# External notifications (email / SMS / push)

In-app notifications are production-ready (`notifications` table + UI).

Email delivery uses `EmailNotificationPublisher`, which is currently a **safe stub**:
it logs at debug and never throws into financial transaction flows.

SMS and browser push are not implemented. Keep `NotificationFacade` / channel
abstractions; configure real providers later with environment credentials —
do not invent SMTP passwords in source control.
