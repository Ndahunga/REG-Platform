-- Phase 4: verification enforcement.
--
-- From now on a new payment is verified and posted only on credible, independently confirmed receipt of funds
-- (app.payments.verification.require-trusted-confirmation, on by default). Payments that were already VERIFIED, POSTED
-- or REVERSED when this migration ran were accepted under the earlier rules and carry their evidence as recorded
-- (or are held until evidence is completed). They are flagged here, once, as a legacy exception so the difference is
-- explicit and visible; no other row ever gets the flag.

ALTER TABLE member_payments ADD COLUMN legacy_verification_exception BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE member_payments
   SET legacy_verification_exception = TRUE
 WHERE status IN ('VERIFIED', 'POSTED', 'REVERSED');
