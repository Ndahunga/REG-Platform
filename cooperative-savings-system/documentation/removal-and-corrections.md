# Removing things safely: delete, deactivate, cancel, reverse

This is a financial system. The rule is not "every screen gets a trash icon"; it is that **nothing that has touched money
can disappear**. Each kind of record therefore has the one removal that fits it, and the backend (never the screen)
decides whether that removal is allowed.

| Word on the screen | What it really does |
|---|---|
| **Delete** | Permanently removes a record that has no history and nothing depending on it. Audited. |
| **Deactivate** | Keeps the record and every record that refers to it; it just stops being used for new things. |
| **Withdraw / Cancel / Reject** | Ends something that has not moved any money yet, and keeps the record. |
| **Reverse** | Undoes posted money with compensating entries. The original and the reversal both stay. |

Every removal is permission-checked on the server, scoped to the caller's own Saving Scheme (another scheme's record
answers "not found", never "forbidden", so nothing leaks), and audited.

## Coverage

| Entity / screen | Add | Edit | Delete | Deactivate / archive | Cancel / void / reverse | Rule |
|---|---|---|---|---|---|---|
| **Member** | yes | yes | **only with no history at all** | **yes** (leaves active membership; reactivate) | n/a | Delete is possible only if no record of any kind refers to the member (financial, audit, or actions they performed) and they belong to no other scheme. Everyone else is deactivated. `MEMBERSHIP_MANAGE` + President/Vice President. |
| **Officer / staff** (a member with a role) | yes (role change) | yes | same as member | same as member | n/a | An officer who reviewed, verified or approved anything is on record, so they are deactivated; history still names them. |
| **Platform administrator** | yes | n/a | no | yes (status) | n/a | Platform accounts are changed only by other platform administrators; the last one cannot be disabled. |
| **Saving Scheme (Ikimina)** | yes | yes | no | yes (status) | n/a | A scheme owns financial history; it can be switched off, never removed. |
| **Payment account** | yes | no | **only if never used** | **yes** | n/a | "Used" = a payment through its channel, or a receipt, was handled while it was registered. Then it is only deactivated. `SETTINGS_MANAGE` / `COOPERATIVE_WRITE`. |
| **Rotation configuration** (rotating/retained split) | yes | no (insert-only) | **only if not yet in effect and unused** | superseded by a newer one | n/a | In force or used to split contributions = history; add a newer configuration. `COOPERATIVE_WRITE`. |
| **Rotation cycle / schedule** | yes | recipient before the month | no | cancel (`/cycles/{id}/cancel`) | n/a | A cycle is cancelled, which keeps what was collected on it for a separately approved resolution. |
| **Rotating pool entry** | yes | no (insert-only) | no | n/a | corrected by a new entry | An earmark with no ledger effect; fund authority only. |
| **Member payment** | yes | n/a | **never** | n/a | **withdraw** (payer, only before review) / **reject** (officer, before verification) / **reverse** (once posted) | Verified payments stay until posted; posted payments are only reversed. |
| **Payment reversal request** | yes | n/a | no | n/a | withdraw / decline / approve | Executed atomically by an independent fund authority. |
| **Member credit** | automatic | n/a | no | n/a | taken back only with its payment while unspent | Applied credit blocks the reversal of its payment (see below). |
| **Loan** | yes | no | **never** | n/a | reject (before disbursement), write off, repayment reversal | A loan's history is its balance; it is closed or written off, never removed. |
| **Loan repayment** | yes | n/a | no | n/a | reversed with its payment (compensating entries) | Refused if a later repayment, a penalty portion or a write-off is involved. |
| **Fine** | yes | yes (unpaid) | "remove" = **cancel** (kept) | waive | cancel / waive | Paid fines are never touched. |
| **Income / expense transaction** | yes | n/a | **never** once recorded | n/a | reject while pending; reverse/adjust once approved | |
| **Share purchase** | yes | n/a | no | n/a | reject while pending | |
| **Investment** | yes | yes | no | n/a | cancel (before activation) | |
| **Social-fund disbursement** | yes | n/a | no | n/a | cancel (before payment) | |
| **Rotation payout** | yes | n/a | **never** | n/a | cancel (requested/approved), reject; a disbursed payout is final | Completed payouts are history. |
| **Historical import (opening balances / loans)** | yes | n/a | no | n/a | **cancel** while unconfirmed; none once confirmed | A confirmed import is part of the opening position. |
| **Historical transaction batch** | yes | n/a | no | n/a | **cancel** while previewed; none once posted | |
| **Migration strategy** | yes | change only before anything is imported | no | n/a | review resolved by fund authority, audited | |
| **Contribution due-day / loan / share-price settings** | n/a | yes | no | effective-dated or superseded | n/a | Settings are replaced by a newer value; the old value stays with the records it governed. |
| **Notifications** | automatic | read/unread | no | n/a | n/a | Not financial; not removable by users. |

Database safety: no foreign key in this schema cascades (`ON DELETE CASCADE` is not used anywhere) and no JPA mapping
cascades or removes orphans, so a deletion can never silently erase financial rows; a stray reference makes the database
refuse it. The only hard deletes in the code are the four above (unused member, unused payment account, unused rotation
configuration) and the replacement of a scheme's loan-share tier rows when its loan settings are saved.

## Members

* **Delete (permanent)** only when the member has no history of any kind: nothing in the scheme refers to them
  (no contribution, payment, loan, repayment, credit, share, fine, social-fund record, payout, rotation position or opening
  balance), no record of any table points at them (this includes every action they performed, audit records included),
  and they belong to no other scheme. The backend reads the database's own foreign keys to decide, so a table added later
  is covered automatically. Signing in alone is not history.
* **Deactivate** for everyone else. It changes only this scheme's membership to `INACTIVE` and records who, when and why.
  * *Access and new activity:* the member can no longer use the scheme, submit payments, request loans, buy shares or be
    given new obligations.
  * *Loans:* an outstanding loan stays outstanding, with its interest and schedule, and an officer can still record
    repayments. Deactivation forgives nothing.
  * *Contributions:* arrears and obligations already on the books stay owed and visible.
  * *Rotation:* the member's obligations stay; their position in the schedule is not deleted. A payout for them waits
    until they are reactivated or the recipient is changed. A member with a payout in flight cannot be deactivated until
    it is settled.
  * *Member credit* stays held.
  * *Reports:* every historical report still includes everything the member did. Only "active member" counts exclude them.
  * Nobody can remove themselves, and platform administrator accounts cannot be changed through scheme management.
* **Reactivate** restores active membership.
* The old generic status endpoint can no longer be used to set `INACTIVE` (it would skip these rules); it answers
  `USE_MEMBER_DEACTIVATE`.

## Correcting a payment whose credit was already applied

If a payment created member credit, part of that credit was applied to a contribution or a loan, and the payment is later
found to be wrong, the reversal of the payment is **refused** (`REVERSAL_BLOCKED_CREDIT_APPLIED`) and nothing changes:
the credit journal, the ledger, the contribution and the payment stay exactly as they were. This is deliberate. Reversing
the payment would have to take back money that has since been spent on something else, and doing it half-way would
drive the member's credit negative or leave the ledger and the credit journal disagreeing.

There is no safe operator-only correction for this case in this release (editing the contribution directly would leave
the credit journal claiming credit was applied that no longer is). Until an "unapply credit" workflow exists:

1. Do not edit or delete anything. Record the facts: payment id, the credit entries, what was applied and to what.
2. Reverse what *can* be reversed first, in order: later repayments or applications that depend on the credit.
3. Escalate to the platform administrators. The correction is a developer-assisted, two-authority compensating entry
   set (unapply the credit entry, then reverse the payment), reviewed against the split and credit reconciliations
   (`GET /ledger/split-reconciliation`, the member credit history) before and after.
4. The proposed future feature is a compensating `APPLIED_REVERSED` credit entry through the same engines, with the same
   independence, idempotency and atomicity as the payment reversal. It was not built here because it touches rotation
   distributions and loan schedules and must not be rushed.

## Migration strategy review (`needs_review`)

Schemes that, before the safeguard, had brought their history in both ways are flagged by migration V44. They keep working
normally; only historical imports are refused (`MIGRATION_NEEDS_REVIEW`). Platform administrators see them on their home
page and at `GET /api/v1/platform/migration-reviews`; the scheme's officers see a notice on the import page. The President
or Vice President (fund authority) resolves the review by naming the authoritative strategy and explaining how the scheme
was reconciled (at least 20 characters); this is audited (`MIGRATION_STRATEGY_RESOLVED`) and never guessed. Resolving does
not change any figure: duplicated history must first be corrected through the normal correction workflows.
