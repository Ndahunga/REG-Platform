# Handover: share-based rotation, member positions and migration history

This repository is OuWealth-Community as of `b3046c2` (18 Oct 2026), imported in commit `e9fda46`, plus the changes
below. Everything after `e9fda46` is new work. CI (backend tests, frontend tests and build, and an end-to-end seed of
demo schemes on PostgreSQL) passes on `main`.

## How to bring it to production

1. Take the changes: `git diff e9fda46..main -- cooperative-savings-system/backend cooperative-savings-system/frontend`
   (or cherry-pick the commits). Nothing outside those two folders is needed in production:
   `cooperative-savings-system/staging/`, `.github/` and `HANDOVER.md` are for staging and CI only.
2. Two new Flyway migrations run on start-up: `V47__share_rotation_and_positions.sql` and
   `V48__loan_per_share_and_penalty_bands.sql`. Both only add columns, tables and constraints; no existing data is
   rewritten. Take a database backup before deploying, as usual.
3. No new environment variables. One new scheduled job (operating model changes, daily at 00:05 Kigali; cron property
   `app.operating-model.apply-cron`).
4. Deploy backend and frontend together (the frontend calls the new endpoints).
5. After deploying: open Settings, Rotation, Reports and Data migration on an existing scheme and check they load.

## What changed (for every scheme type: accumulating, rotating, hybrid)

### Rotation
- **Share-based cycles** (`FIXED_PER_SHARE`): every share has one turn worth a fixed payout per share (e.g. 50,000 x
  12 = 600,000). A month can pay several members; a member with several shares can be paid share by share, all
  together, or grouped by month (`turn_mode`). New table `rotation_turns`; endpoint `POST /rotation/share-cycles`;
  turns can be reassigned before their month (`PUT /rotation/turns/{id}/member`). Old one-recipient-per-month cycles
  keep working unchanged.
- **Whole cycle owed from the start**: activating a share-based cycle creates every month's contribution obligation,
  so a member paid early sees each month still to contribute.
- **Payouts by turn** are paid from the scheme's rotation pool (verified rotated collections plus reserve, less what
  was paid). A payout is **netted only by the recipient's unpaid contributions of months already due** (never loans or
  fines): the netted part settles those contributions at disbursement (`rotation_payout_offsets`).
- **Hybrid split in money**: the rotated amount per share is stated in RWF (e.g. 50,000 of 70,000) and split exactly
  (`rotating_unit_amount`), instead of rounded percentages.
- Officers now see Rotation in their menu; the President/Vice President can set the operating model in Settings.

### Capital, income and statements
- **Member positions** (`GET /positions`, `/positions/me`, CSV export, Reports page): capital grows by what each member
  keeps in the scheme (an accumulating payment of 40,000 adds 40,000 to that member; in a hybrid only the retained part;
  a pure rotating scheme has no capital), **income is shared equally per share**, the rotation position shows what the
  scheme owes the member (positive) or the interest-free advance the member is repaying (negative), plus loans, fines,
  amounts owed to the member and the net. A scheme reconciliation compares members' totals with cash + loans out.
- **Share-out** weights only retained capital (rotated money no longer counts).

### Loans and penalties
- **Loan limit per share** held, and an option to **refuse a loan while a contribution is overdue** (Loans > Settings).
- **Late penalties by bands of days late**, optionally **per share** (Fines > Settings).
- **Unpaid contribution converted to credit**: after N days late the unpaid amount becomes a loan at X% a month over
  M months, and the contribution counts as paid by that credit (no cash moves). Runs with the nightly fine job.
- Expenses and social-fund disbursements now take the scheme fund lock and cannot exceed available funds.

### Operating model changes
- A scheme can change model (e.g. accumulating to hybrid) **from a month that has not begun**; it can be cancelled
  until then (Settings > Operating model). Unpaid obligations of the new months take the new model.

### Migration (history import) — three workbooks, in this order
1. **Members & share capital** (unchanged template): one row per member; share count and share capital per member.
   Cutover date and cash at the cutover are entered on the import screen.
2. **Opening loans** (unchanged template): `Repayment Type` must be `END_OF_TERM` or `MONTHLY`; an interest-free
   receivable is a loan at 0%.
3. **Rotation & positions** (new, Data migration > Rotation & positions tab, `GET/POST /positions/opening-workbook`):
   - *Scheme* sheet: cycle first month, months, payout per share, rotated amount per share, how shares are paid,
     rotation money held at the cutover, income earned before the cutover not yet shared.
   - *Turns* sheet: member, turn month, shares, amount already received before the cutover.
   - *Members* sheet: rotated money each member paid before the cutover; amount the scheme owes the member.
   The import checks rotated money paid less payouts received against the rotation cash and reports any difference.
   An accumulating scheme leaves the cycle rows empty.

The Rotation & positions workbook has a row "First month collected in the app (YYYY-MM)": obligations start from that
month, whatever day the import is run (e.g. 2026-09 for an August cut-off imported in October).

## Second release: corrections, permanent deletion, reports (migration `V49__deletion_records_and_import_revisions.sql`)

### Correcting an upload (no more "import once")
- Members & share capital, Opening loans and Rotation & positions can each be uploaded again after they were validated.
  The new upload is a **correction**: it is checked against the scheme as it would be with the earlier upload undone
  (nothing changes while checking), needs a reason, and on **Validate** replaces the earlier upload in one transaction.
  The earlier upload stays listed with status `SUPERSEDED`, who replaced it, when and why (audit action `IMPORT_REVISE`).
- Members: opening balances and share counts are replaced; corrected names, emails and phones are applied to the
  accounts the earlier upload created; rotation positions already imported are kept; a member left out is removed when
  nothing else was recorded for them (otherwise kept and named).
- Loans: a loan identical in both versions stays as it is (with repayments recorded since); a changed or removed loan is
  replaced, unless something was recorded on it since, in which case validation stops and names the loan.
- Rotation: the cycle is rebuilt from the corrected workbook; payouts, payments and recipient changes recorded since move
  to the same month and member of the corrected cycle (refused, with the reason, when the correction drops that turn).
- The button "Confirm Import" is now **Validate**.

### Permanent deletion
- **Scheme** (platform administrators, scheme page > Delete permanently): preview of what goes, type the scheme name,
  give a reason. Every row of the scheme is deleted, plus member accounts that belong to no other scheme. Audit logs and a
  `data_deletion_records` row stay. `GET/POST /cooperatives/{id}/permanent-deletion`.
- **Member** (President / Vice President, member > Remove > Delete member and all records): deletes the member and all
  their records in the scheme (contributions, payments, loans, fines, shares, opening balances, turns and their ledger
  lines). Refused when the member recorded or approved other members' records as an officer, or is the only recipient of
  a rotation month. `GET/POST /cooperatives/{c}/members/{m}/full-deletion`.
- Both follow foreign keys read from the database (`shared/erasure/DataGraph`), so tables added later are covered.

### Reports
- New report types, each in PDF (same logo, title and fonts as before) or Excel (`format: EXCEL`): Member statement,
  Reconciliation, Collection by month, Rotation schedule, Member positions, Arrears and penalties, Loan book, Income and
  expenses, Year-end close. Members can export their own statement. `modules/report/service/StatementReportService.java`.

## Files to look at first
- Backend: `modules/rotation/service/RotationCycleService.java`, `modules/rotation/payout/RotationPayoutService.java`,
  `modules/rotation/pool/RotationPoolService.java`, `modules/position/MemberPositionService.java`,
  `modules/rotation/service/OpeningPositionsWorkbookService.java`, `modules/fine/service/CreditConversionService.java`,
  `modules/cooperative/service/OperatingModelChangeService.java`.
- Tests: `ShareRotationAndPolicyIntegrationTest`, `ContributionSplitByUnitsTest`.
- Frontend: `features/rotation/ShareCycleDialog.tsx`, `TurnPayoutsSection.tsx`, `features/positions/`,
  `features/historicalTransactions/OpeningWorkbookPanel.tsx`, `features/cooperatives/ModelChangeSection.tsx`.
