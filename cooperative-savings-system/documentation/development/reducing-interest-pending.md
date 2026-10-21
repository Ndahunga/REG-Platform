# Reducing-balance interest — pending business rule

**Status:** Blocked for new settings and new loans  
**Date:** 2026-08-10

## Why

`LoanInterestCalculator` currently applies the same simple percentage for both `FLAT` and `REDUCING`. That is not a true reducing-balance amortization schedule. Inventing a client formula would risk incorrect interest on live loans.

## Current behavior

- **FLAT** — supported; interest = principal × rate/100 (one-time flat charge).
- **NEW** loan settings / loan requests with `InterestType.REDUCING` are rejected with a clear validation error.
- **Existing** loans already stored as `REDUCING` remain readable and continue to use the legacy calculator path without formula changes (no silent recalculation of history).

## When to unblock

Confirm the cooperative’s reducing-balance rule (schedule, compounding, rounding, early repayment) with the client, then implement it in `LoanInterestCalculator`, add strong tests, and remove the create/update guards in `LoanSettingsService` / `LoanService`.
