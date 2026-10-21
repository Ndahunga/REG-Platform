# Phase 7 completion — Social Fund

**Date:** 2026-08-04  
**Status:** Complete

## Delivered

- Social contributions (submit → approve/reject)
- Social disbursements (request → approve/reject/cancel)
- Separate social fund balance = approved contributions − approved disbursements
- Disbursements blocked when exceeding available social balance
- SOCIAL_READ / SOCIAL_WRITE permissions
- Ledger SOCIAL_CONTRIBUTION credits and SOCIAL_DISBURSEMENT debits
- Social metrics on dashboard (separate from available group funds)
- Light JSON report by date range
- Frontend overview, contributions, disbursements, report tabs

## Isolation

Social fund amounts are **not** included in `availableGroupFunds`.

## Next phase

**Phase 8 — Investments and Transactions**

- Investments and returns
- Income and expenses
- Full ledger exposure
- Richer available-fund formula
