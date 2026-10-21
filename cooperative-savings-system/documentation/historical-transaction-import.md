# Historical transaction import

Imports the dated history of an existing Saving Scheme across several years. It is separate from the opening-balance
migration (which records balances at one cutover date) and does not replace it.

## Workflow

1. **Download the template** (Historical import, tab "Historical transactions"). It has exactly two worksheets and no
   sample rows. Column notes are attached to the header cells.
2. **Fill it in** and **upload** it. Upload validates the file and stores the result as a *dry run*: nothing is posted.
   Uploading the same file again returns the same batch.
3. **Review** the per-row errors, warnings, the per-year totals and the opening/closing reconciliation.
4. A **different officer with fund authority** approves. The uploader can never approve (Super Admin included). The batch
   is validated again against the current data, then posted in **one transaction**. A reconciliation variance can only be
   approved with a written explanation (at least 10 characters), which is recorded.
5. Posting is **final**. Nothing posted is edited or deleted. A batch can be cancelled only before it is posted.

## Worksheet 1: Members and Configuration

| Column | Meaning |
|---|---|
| Row Type | `MEMBER` or `SETTING` |
| Username | `MEMBER`: an existing member of this scheme. Members are never created by this import. |
| Full Name, Share Count | Informational. A differing share count is a warning; shares are never changed. |
| Setting / Value | `OPENING_BALANCE` and `DECLARED_CLOSING_BALANCE` (both required), `HISTORY_END_DATE`, `CURRENCY`, `OPERATING_MODEL` |

## Worksheet 2: Historical Transactions

Columns: Row Type, Date, Member Username, Amount, Original Reference, Period Year, Period Month, Loan Reference,
Operating Model At The Time, Description.

| Row Type | Ledger effect | Notes |
|---|---|---|
| `CONTRIBUTION` | credit | Needs a member, a period and a date. Recorded as a historical contribution obligation. |
| `FINE_PAYMENT` | credit | Recorded as a fully paid fine with its approved payment. |
| `OTHER_INCOME` | credit | No member needed. |
| `GENERAL_EXPENSE` | debit | No member needed. |

Any other row type (share purchases, dividends, social fund, investments, loan reversals, write-offs) is **rejected with an
explicit error**. Nothing is guessed.

**Loans and member payouts are not importable.** `LOAN_DISBURSEMENT`, `LOAN_PRINCIPAL_REPAYMENT`, `LOAN_INTEREST_PAYMENT`
and `MEMBER_PAYOUT` are recognised only to be refused during the dry run, with an explanation. A loan needs an instalment
schedule and an interest and penalty breakdown, and a payout needs a rotation period and recipient; a dated list of cash
movements contains none of them, so importing them would leave the loan and payout records empty while the ledger looked
reconciled. Use the **opening-balance loan migration** for loans that are still open. Batches posted by an earlier version
that included such rows are left as they are: nothing is deleted or rewritten.

## Rules the import enforces

- **Dates** are the original dates: not in the future, not before 2000, not after `HISTORY_END_DATE`, and **before the
  first live ledger entry** of the scheme, so history is never counted twice.
- **Duplicates** are detected inside the file, against existing contributions for the same member and period, and against
  every event already imported (the database refuses a repeat as well).
- **Loans and payouts** are refused (see above). Nothing is ever posted to the ledger alone.
- **Reconciliation:** opening balance + money in - money out = computed closing balance, compared with the declared
  closing balance, with per-year totals.
- **Attribution:** the import never invents an obligation snapshot, rotation period or recipient. Imported contributions
  therefore never become verified rotating payout entitlement. If `Operating Model At The Time` is `ACCUMULATING` the
  amount is retained capital; if it is `ROTATING` it is held as rotating money whose period is unresolved (not payable,
  not member capital); if it is blank the class is unknown and is reported on its own.

## Not available

Attributing historical rotating money to an original rotation period and recipient needs a policy that has not been
approved. Until then such money stays held and payout-ineligible.
