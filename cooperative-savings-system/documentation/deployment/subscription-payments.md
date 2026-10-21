# OuWealth subscription payments — sandbox & production

This document covers Phase 7 readiness for OuWealth cooperative subscription billing.

Providers:

- **MTN_MOMO** → direct MTN Collection API (not Flutterwave MoMo)
- **CARD** → Flutterwave hosted checkout (never raw card data in OuWealth)

Automatic recurring charging and Super Admin “mark paid” are **not** implemented.

---

## Source of truth

All provider settings bind through `SubscriptionProperties` (`app.subscription.payment.*`) from `application.yml` / environment variables. Do not hard-code domains or secrets in payment clients.

---

## Environment variables

### MTN MoMo

| Variable | Role | Notes |
|---|---|---|
| `MTN_MOMO_ENABLED` | Master switch | `false` for local/dev without keys |
| `MTN_MOMO_BASE_URL` | API host | Sandbox default: `https://sandbox.momodeveloper.mtn.com` |
| `MTN_MOMO_SUBSCRIPTION_KEY` | **Secret** | Collection subscription key |
| `MTN_MOMO_API_USER` | **Secret** | API user UUID |
| `MTN_MOMO_API_KEY` | **Secret** | API key |
| `MTN_MOMO_TARGET_ENVIRONMENT` | Explicit mode | `sandbox` (dev) or production value e.g. `mtnrwanda` — **never inferred from URL** |
| `MTN_MOMO_CALLBACK_URL` | Public URL | Backend callback, e.g. `https://<api>/api/v1/public/billing/mtn/callback` |

Production profile fails startup if MoMo is enabled with `sandbox` target environment or a non-HTTPS / localhost callback URL.

### Flutterwave

| Variable | Role | Notes |
|---|---|---|
| `FLUTTERWAVE_ENABLED` | Master switch | |
| `FLUTTERWAVE_BASE_URL` | API host | Usually `https://api.flutterwave.com/v3` |
| `FLUTTERWAVE_MODE` | Explicit mode | `test` or `live` — **never inferred from secret contents** |
| `FLUTTERWAVE_PUBLIC_KEY` | Public | Optional for hosted Standard checkout |
| `FLUTTERWAVE_SECRET_KEY` | **Secret** | Server-only |
| `FLUTTERWAVE_SECRET_HASH` | **Secret** | Dashboard secret hash ↔ `verif-hash` header |
| `FLUTTERWAVE_REDIRECT_URL` | Public frontend URL | e.g. `https://<app>/billing/payment-return` |
| `FLUTTERWAVE_WEBHOOK_URL` | Documented webhook URL | Must match dashboard registration |

Production profile fails if Flutterwave is enabled with `FLUTTERWAVE_MODE=test` or unsafe redirect/webhook URLs.

### Optional / shared

| Variable | Default | Meaning |
|---|---|---|
| `SUBSCRIPTION_PENDING_REUSE_MINUTES` | `15` | Checkout retry eligibility (same plan/channel/payer). **Not** provider final status. |
| `SUBSCRIPTION_PENDING_ABANDON_HOURS` | `48` | PENDING older than this are skipped by reconciliation sweeps (still PENDING until callback/status/new checkout). |
| `SUBSCRIPTION_RECONCILIATION_ENABLED` | `false` | Enable missed-callback recovery job |
| `SUBSCRIPTION_RECONCILIATION_FIXED_DELAY_MS` | `300000` | 5 minutes between sweeps |
| `SUBSCRIPTION_RECONCILIATION_MIN_AGE_MINUTES` | `2` | Skip brand-new PENDING so callbacks can win |
| `SUBSCRIPTION_RECONCILIATION_BATCH_SIZE` | `50` | Max payments per sweep |

---

## Pending / temporary-error policy

| Situation | Local status |
|---|---|
| Checkout created | `PENDING` |
| Provider timeout / 5xx / connection error (`UNKNOWN`) | Stay `PENDING` (`verificationUnavailable` for UI) |
| Provider reports still pending | Stay `PENDING` |
| Provider confirms failed / canceled | `FAILED` / `CANCELED` |
| Provider confirms success + amount/currency/tx_ref match | `SUCCESS` + activate once |
| Pending reuse window expired | Does **not** mark FAILED; new checkout may cancel/replace stale PENDING |
| Older than abandon hours | Left PENDING; excluded from scheduled reconciliation only |

---

## Reconciliation strategy

When enabled, `SubscriptionPaymentReconciliationJob` runs on a fixed delay (default 5 minutes). It selects eligible `PENDING` payments, locks each row, and calls the same `BillingService.synchronizeWithProvider` path used by callbacks, webhooks, and status polling. Activation always goes through `SubscriptionActivationService.applySuccessfulPayment` (idempotent).

---

## Manual sandbox test flow

1. Enable one provider with **sandbox/test** credentials (local or staging profile — not production).
2. Register callback/webhook/redirect URLs in the provider dashboard.
3. As a billing manager, open **Billing**, choose Monthly/Annual, start checkout.
4. Complete or abandon payment in the provider UI.
5. Confirm OuWealth status via **Check status** / return page / webhook.
6. Verify `SubscriptionPayment` status and cooperative subscription entitlement.
7. Confirm **no** `financial_ledger` row for the platform subscription fee.

---

## Deployment checklist

### Backend (e.g. Render)

1. Set JWT + Postgres secrets.
2. Set MTN and/or Flutterwave variables (secrets + public URLs).
3. Set `MTN_MOMO_TARGET_ENVIRONMENT` / `FLUTTERWAVE_MODE` to production values only on the production profile.
4. Set `SUBSCRIPTION_RECONCILIATION_ENABLED=true`.
5. Confirm Flyway migrates through latest (includes `V24` provider refs / `payer_msisdn`).
6. Confirm HTTPS termination and CORS allow the frontend origin.

### Frontend (e.g. Vercel)

1. Point API base URL at the production backend.
2. No payment secrets in frontend env.
3. Ensure `/billing/payment-return` is routable (SPA rewrite).

### MTN portal

1. Create Collection API user / key / subscription key for the target environment.
2. Register callback URL: `https://<api-host>/api/v1/public/billing/mtn/callback`.
3. Use sandbox numbers for sandbox tests.

### Flutterwave dashboard

1. Use **test** keys for sandbox; **live** keys only for production.
2. Set Secret Hash (webhook verification).
3. Register webhook: `https://<api-host>/api/v1/public/billing/flutterwave/webhook`.
4. Confirm redirect URL matches `FLUTTERWAVE_REDIRECT_URL` (`…/billing/payment-return`).

### Security

- Never commit real keys.
- Rotate secrets if leaked.
- Production requires HTTPS public URLs (no localhost).
- OuWealth never collects PAN, CVV, expiry, or MoMo PIN.

### Go-live test order

1. Provider sandbox/test payment  
2. Small controlled payment  
3. Verify webhook/callback received  
4. Verify `SubscriptionPayment` = SUCCESS  
5. Verify subscription ACTIVE / extended  
6. Verify no cooperative `financial_ledger` entry for the fee  

---

## Remaining blockers to a real sandbox payment

1. Real MTN sandbox credentials + registered callback URL reachable from MTN  
2. Real Flutterwave test keys + secret hash + webhook URL reachable from Flutterwave  
3. Deployed backend/frontend URLs (or a tunnel) for callbacks  
4. Billing manager user with email (card) / Rwandan phone (MTN)  
