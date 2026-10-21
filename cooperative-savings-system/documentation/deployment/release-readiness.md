# Release readiness: member payments, rotation, verification and historical import

Preparation only. Nothing here has been executed against staging or production.

## 1. What this release contains

| Area | Status |
|---|---|
| Member payments with several allocations, independent verification, atomic posting | Implemented |
| Fund lock, contribution-only reversal with approval, rotation attribution and payouts | Implemented |
| Rotating, hybrid and accumulating operating models, NAV and financial reports | Implemented |
| Trusted confirmation (cash by an officer, statement-line import, signed callback) | Implemented |
| Legacy reconciliation of rotating contributions recorded without verification | Implemented |
| Historical import: contributions, fine payments, other income, general expenses | Implemented |
| Opening-balance loan migration (existing) | Implemented, the only supported route for loans |
| Late fines on effective-dated due days | Implemented |
| Generic signed provider callback (HMAC, signed timestamp, replay window, rate limits) | Implemented, **needs a sender and a secret** |
| Receipt reading (OCR) | Implemented, **needs Tesseract on the server**; advisory only |
| Provider-specific integrations (MTN MoMo, Airtel, banks) | **Not built**: no provider credentials or formats exist here |
| Historical loans and member payouts | **Not importable** (rejected in the dry run) |
| Earnings distribution and dividends | **Blocked by an unapproved policy**: reports show UNAVAILABLE_PENDING_POLICY |
| Attribution of historical rotating money to periods and recipients | **Blocked by policy**: stays unresolved and payout-ineligible |
| Whether unknown-class money reduces NAV | **Blocked by policy**: reported, not subtracted |

## 2. Recommended commit strategy

The working tree is one large uncommitted change. Commit it as reviewable steps on a feature branch, in this order, each
compiling and passing its own tests where practical (the migrations are numbered and must stay in order):

1. Scheme operating model, obligations and fund lock (V29, V30, V32 and their code and tests).
2. Member payment foundation and verified posting (V28, V33).
3. Reversals (V34, V36) and rotation configuration, schedule and payouts (V31, V35).
4. Receipt reading, trusted verification, legacy reconciliation (V37, V38).
5. Verification enforcement, financial reports, late fines (V39).
6. Historical transaction import (V40) and documentation.
7. Frontend, in the same order as the backend features it uses.
8. Release hardening: upload content checks, callback timestamp and rate limits, OCR limits, this document.

Do not squash migrations together or renumber them. Line-ending warnings (LF to CRLF) appear on tracked files; agree a
`.gitattributes` policy before committing so the diff is not polluted.

## 3. Required environment variables

Set these in the deployment `.env` (never commit values). All are listed in `.env.example`.

| Variable | Production value |
|---|---|
| `APP_PAYMENTS_REQUIRE_TRUSTED_CONFIRMATION` | `true` (the default; never `false`) |
| `PAYMENT_CALLBACK_SECRET_<CHANNEL>` | Blank until a sender is agreed; then a random 32-byte value per channel |
| `PAYMENT_CALLBACK_REPLAY_WINDOW_SECONDS` | `300` |
| `PAYMENT_CALLBACK_MAX_FUTURE_SKEW_SECONDS` | `60` |
| `PAYMENT_CALLBACK_RATE_PER_ADDRESS_PER_MINUTE` / `..._PER_CHANNEL_PER_MINUTE` | `60` / `600` (0 disables) |
| `APP_OCR_TESSERACT_PATH`, `APP_OCR_TESSERACT_LANGUAGE`, `APP_OCR_TIMEOUT_SECONDS`, `APP_OCR_MAX_BYTES` | Only if Tesseract is installed |
| `APP_SEED_DEFAULT_ADMIN` | `false` |

Callback contract: `POST /api/v1/public/payment-callbacks/{channel}` with headers `X-Timestamp` (epoch seconds) and
`X-Signature` = hex HMAC-SHA256 of `timestamp + "." + raw body`. Refusal codes: 401 `MISSING_TIMESTAMP`,
`INVALID_SIGNATURE`, `INVALID_TIMESTAMP`, `STALE_TIMESTAMP`, `FUTURE_TIMESTAMP`; 429 `RATE_LIMITED` (with `Retry-After`);
503 `PROVIDER_NOT_CONFIGURED`. Redelivery inside the window is idempotent by `eventId`.

### Infrastructure rate limiting still required

The in-process limiter protects one application instance and sees the proxy's address, not the client's. The reverse
proxy must also limit the public endpoint, for example in nginx:

```nginx
limit_req_zone $binary_remote_addr zone=callbacks:10m rate=30r/m;
location /api/v1/public/payment-callbacks/ {
    limit_req zone=callbacks burst=20 nodelay;
    client_max_body_size 32k;
    proxy_pass http://backend;   # same upstream as /api/
}
```

`infrastructure/nginx/nginx.conf` does not contain this yet. If the application sits behind a proxy, configure forwarded
headers so `getRemoteAddr()` reflects the real client, or rely on the proxy limit alone.

## 4. Database backup and migration precautions

- Take a full backup (`pg_dump -Fc`) immediately before deploying, and verify it restores into a scratch database.
- Migrations V28 to V40 are additive: new tables and columns. The only drops are constraints on tables created in this
  same release (V36). V39 flags rows that are already VERIFIED, POSTED or REVERSED as legacy verification exceptions,
  once; no later row gets the flag.
- Flyway runs forward only. Rolling back the application image does not undo the schema; the older code ignores the new
  tables. Do not run `flyway clean` or `undo` anywhere that holds real data.
- Run staging against a restored copy of production data (sanitised) first, to time V39 and V40 and to look at the
  legacy exceptions it flags.
- Confirm the database user can create tables, constraints and indexes for the migration run.

## 5. Staging deployment checklist

1. Branch deployed to staging with the staging profile; `APP_PAYMENTS_REQUIRE_TRUSTED_CONFIRMATION=true`.
2. Flyway reports V1 to V40 applied; no failed rows in `flyway_schema_history`.
3. `/api/v1/cooperatives/{id}/payments/verification-capabilities` as an officer shows strict verification on; callback
   and OCR availability match what is configured (not more).
4. Callback secrets either blank (503 everywhere) or set for a test sender only.
5. Reverse proxy limits and body-size limit on the callback path in place.
6. Logs show no secrets, tokens or payment evidence.

## 6. Financial smoke tests (staging, test scheme only)

1. Register a hybrid scheme, 4 members, 2 recipients, 2 officers, 1 President.
2. Member submits a payment covering two months; nothing changes in balances.
3. Statement line imported by officer A; officer B verifies; officer C posts. Balances change once. Re-posting does nothing.
4. Same reference submitted again is refused; the same statement line cannot confirm a second payment.
5. Cash payment: officer confirms cash, a different officer verifies, a third posts.
6. Reversal request, approval by a fund authority who is not the requester; balances return; the payment cannot be re-posted.
7. Rotation: recipient entitlement appears; payout request, approval, confirmation; a second confirmation is idempotent; an over-request is refused.
8. Callback: a correctly signed request is accepted; the same request after the window, a changed body and a changed timestamp are refused.
9. Historical import: download the template; a file with a loan or payout row is refused with the explanation; a valid file previews, a different officer approves, the same file again is not a second batch.
10. Financial report and model summary: retained plus rotating plus unknown equals collections; NAV counts cash once.
11. Switch tenants: another scheme's payment, import or report is 404 or 403.
12. Mobile and dark mode, English and Kinyarwanda on the payment, rotation, report and import screens.

## 7. Rollback considerations

- **Application rollback** is an image swap; the schema stays at V40 and old code does not use the new tables.
- **Disabling behaviour without redeploying:** blank the callback secrets (endpoint answers 503). Do not relax
  `APP_PAYMENTS_REQUIRE_TRUSTED_CONFIRMATION`.
- **Data rollback** is restore-from-backup only, and loses everything written after the backup. Decide the cut-off
  before go-live: once members' payments post, prefer fixing forward with the reversal workflow.
- Posted historical imports are final by design and are never edited or deleted; a mistaken batch is corrected by
  reversing entries, not by deleting rows.

## 8. Open items before general release

See the final release-gate report for the BLOCKER, IMPORTANT and OPTIONAL list.

## 9. Audit-remediation release: migrations V41 to V46

Preparation only. Not executed against staging or production. Everything below is additive (new tables, columns and
constraints); no data is rewritten.

| Migration | What it adds | Deployment risk |
|---|---|---|
| V41 | Receipt analyses and the scheme's designated payment accounts | None for existing data. Needs Tesseract for image receipts; PDF receipts need no extra software |
| V42 | Member credit (journal of excess payments), credit purpose on allocations | None for existing data. Credit is never income and is excluded from available funds |
| V43 | Loan-payment reversal: `loan_repayments.reversed_at/by` and the immutable `member_payment_loan_reversals` | None for existing data. Reversals that cannot be done safely are refused, never guessed |
| V44 | One migration strategy per scheme; `historical_imports.import_kind` | **Backfills from existing imports.** A scheme that imported both ways is flagged `needs_review` and accepts no further import until a person reconciles it. Query `cooperative_migration_strategies where needs_review` before go-live |
| V45 | `financial_ledger.rotating_amount/retained_amount` with CHECKs (parts add up to the entry) | Existing contribution entries stay unclassified (NULL); the split reconciliation reports them. No backfill is attempted in SQL |
| V46 | Rotating pool earmarks (`rotation_pool_entries`) | None for existing data. The earmarks create no ledger entry and move no cash |

Behaviour to know about before release:

- **Opening-balance workbook is two sheets** (Member Registration, Share Capital Registration). Loans are imported
  separately with the opening-loans template, after the opening balances, once per scheme. A workbook that still carries a
  Loan Registration sheet is refused with an explanation. Tell officers before they download an old template.
- **A scheme brings its past in one way only** (opening balances or detailed transaction history). The first confirmed
  import fixes it; an officer may also choose it in advance (`PUT /cooperatives/{id}/migration-strategy`).
- **Rotation payouts**: a recipient is paid from the verified collections of their own period, plus (only for the part of
  the scheduled amount those collections did not reach) the unused opening pool. A pool entry must be backed by funds that
  are not already owed to recipients. Record the opening pool before the first payout of the scheme.
- **Payment reversal** now covers loan repayments and unspent member credit. Credit that was already applied blocks the
  reversal of its payment (`REVERSAL_BLOCKED_CREDIT_APPLIED`); unapplying credit is not built.
- **Receipt wording**: optional extra phrases can be added with the `APP_PAYMENTS_RECEIPTVOCABULARY_*` variables in
  `.env.example`; the built-in vocabulary always applies.

Smoke tests to add to section 6 on staging: (a) a payment of 80,000 split 30,000 contribution and 50,000 loan, then its
reversal; (b) importing opening balances, then the loans workbook, then trying the transaction-history import (refused);
(c) a rotating scheme with an opening pool, a payout above the period's collections (allowed up to the pool) and one above
that (refused); (d) `GET /ledger/split-reconciliation` reports `balanced`.

## 10. Migration safety review (V41 to V46)

Reviewed together. Order is correct (each migration only refers to tables of earlier ones; V44 reads `historical_imports`
and `historical_ledger_imports` from V17 and V40). No migration rewrites, deletes or re-derives existing financial data.

| Concern | Finding |
|---|---|
| Foreign keys | All new foreign keys point at tables that already exist; none cascades. |
| `ADD COLUMN ... NOT NULL DEFAULT <constant>` (V41, V44) | Metadata-only on PostgreSQL 11+: no table rewrite. |
| `ADD CONSTRAINT ... CHECK` on existing tables | `member_payments` (V41), `member_payment_allocations` and `member_payment_postings` (V42, drop and re-add), `loan_repayments` (V43), `historical_imports` (V44) and **`financial_ledger` (V45, three checks)**. Each takes a brief ACCESS EXCLUSIVE lock and scans the table once to validate. |
| New nullable columns | `financial_ledger.rotating_amount/retained_amount` (V45) are NULL for every existing row; nothing is backfilled in SQL (existing entries are reported as unclassified). |
| V44 backfill | Reads only the two import tables (a handful of rows per scheme) and inserts one row per scheme that already imported. The row id is the scheme id, so no database-specific UUID function is needed. A scheme that imported both ways is flagged `needs_review`. Nothing guesses a strategy for a scheme that imported nothing: it has no row until its first import or an explicit choice. |
| Indexes | New indexes are on new tables only. |
| Hibernate | The PostgreSQL run uses `ddl-auto=validate` against the migrated schema: entities and migrations agree. |
| Rollback | Flyway is forward-only. Application rollback is an image swap (the older code ignores the new tables and columns). Data rollback is restore-from-backup. |

**Production-size ledger.** The V45 checks scan `financial_ledger` while holding a lock that blocks writes to it. On a
ledger of up to a few million rows this is seconds, but do not guess: count the rows first and, for a large ledger, run
V45 in a maintenance window with `SET lock_timeout = '5s'` on the migration session so it fails fast instead of queueing
behind long transactions (then simply retry). The same applies, on a far smaller scale, to `member_payments`.

## 11. Migration checklist (before V41 to V46, and every new migration of this review: V44 now also carries the review columns)

1. Confirm the target is PostgreSQL 11 or later and note the Flyway version already applied (it must be V40).
2. Take a full backup (`pg_dump -Fc`) and prove it restores into a scratch database.
3. Count rows: `select count(*) from financial_ledger, member_payments, loan_repayments;` and decide whether a maintenance
   window is needed (section 10).
4. Check the legacy migration state that V44 will read:
   `select cooperative_id, status, cutover_date from historical_imports where status = 'CONFIRMED';` and
   `select cooperative_id, status from historical_ledger_imports where status = 'POSTED';`
   A scheme appearing in both queries will be flagged for review.
5. Run the migrations in staging against a sanitised copy of production first; time them.
6. Deploy; confirm `flyway_schema_history` shows V41 to V46 successful and the application starts with `ddl-auto=validate`.
7. Afterwards: `select * from cooperative_migration_strategies where needs_review;` and hand that list to the schemes'
   Presidents; they resolve it on the historical import page (audited).
8. Smoke-test (section 12) before opening the system to members.

## 12. Staging smoke tests for this release (realistic RWF examples)

1. **Create a member** (President): register "Ange Uwase". Open "Remove from membership": it offers **Delete member?**
   because she has no activity. Delete her; she disappears and `MEMBER_DELETE` is in the audit log.
2. **Deactivate a member with history**: member "Jean Habimana" has a posted contribution of RWF 30,000. "Remove from
   membership" says **Deactivate member?**, lists what stays on the books, and asks for a reason. After deactivating, his
   contribution, payments and the ledger are unchanged and he can no longer submit a payment. Reactivate him.
3. **Outstanding loan**: Jean has a loan of RWF 100,000 plus RWF 5,000 interest. Deactivate him; the loan still shows
   RWF 100,000 owed and an officer can record a RWF 20,000 repayment (principal then 85,000).
4. **Contribution and combined payment**: a member pays RWF 80,000 split RWF 30,000 contribution and RWF 50,000 loan
   (RWF 45,000 principal, RWF 5,000 interest). Verify with one officer and post with another; then request and approve the
   reversal and check everything returns to the prior figures.
5. **Receipt OCR**: upload a successful MoMo receipt (the member may continue; nothing is verified or posted); a receipt
   saying "Transaction failed" blocks the submission; a "pending" receipt asks the member to wait for the final one; an
   unreadable one needs an explicit manual-review request.
6. **Withdraw**: a member submits RWF 30,000 then withdraws it before anyone reviews it; the reference can be reused.
7. **Member credit**: a payment of RWF 20,000 creates credit; an officer applies RWF 10,000. Requesting the payment's
   reversal is refused with `REVERSAL_BLOCKED_CREDIT_APPLIED` and nothing changes.
8. **Rotation payout**: ten members of RWF 30,000, nine have paid (collected RWF 270,000). Record an opening pool of
   RWF 30,000 (fund authority), then request, approve and disburse RWF 300,000. Without the pool, a request above
   RWF 270,000 is refused. The payout button appears exactly when the eligibility says so.
9. **Payment accounts**: add an MTN account; delete it (never used). Add another, take a payment through it, and see that
   it can only be deactivated.
10. **Rotation configuration**: add a configuration for a future month and delete it; a configuration under which
    contributions were split cannot be deleted.
11. **Historical import**: import opening balances (two-sheet template), then the opening loans (one-sheet template). A
    transaction-history import afterwards is refused with the strategy-conflict message in English and in Kinyarwanda.
    For a scheme flagged for review, imports are refused until the President resolves the review.
12. **Cross-tenant**: with the President of scheme A, try to delete, deactivate or withdraw a member, payment account,
    configuration or payment id of scheme B: every attempt answers "not found" (404) or "forbidden" (403) and changes nothing.

