# Phase 8 completion — Investments, Income/Expenses, Ledger

**Date:** 2026-08-04  
**Status:** Complete

## Delivered

- Investments: plan → activate → capital/profit returns → complete / cancel / loss
- Activate blocked when amount exceeds available group fund
- Income & expenses: OTHER_INCOME, GENERAL_EXPENSE, INTEREST_EXPENSE, ADJUSTMENT with approve/reject
- Approved transactions immutable (corrections via new adjustments/reversals)
- Read-only ledger API with filters
- Fuller available group fund + available interest formulas (social fund still separate)
- Dashboard investment and income/expense metrics
- Frontend investments, transactions, ledger views

## Fund formula (Phase 8)

```
availableGroupFund =
  contribution credits + fine income + other income + capital returns + availableInterest
  − outstanding loan principal − investment outflows − general expenses

availableInterest =
  loan interest credits + investment profit credits − interest expenses
```

## Next phase

**Phase 9 — Payouts**

- Percentage calculations, preview, confirmation, history snapshots
