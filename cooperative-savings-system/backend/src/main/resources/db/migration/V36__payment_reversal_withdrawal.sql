-- The requester of a payment reversal may withdraw it while it is still pending (Phase 2 finalization).
--
-- A withdrawal is recorded in its own columns, not as a decision, so the existing rule that a decision is never made by
-- the requester stays intact. Withdrawing moves no money; it only closes the open request (the one-open-request key is
-- released) and is recorded as an immutable payment event and audit entry by the service.

ALTER TABLE member_payment_reversals ADD COLUMN withdrawn_by UUID;
ALTER TABLE member_payment_reversals ADD COLUMN withdrawn_at TIMESTAMPTZ;
ALTER TABLE member_payment_reversals ADD COLUMN withdrawal_note VARCHAR(2000);

ALTER TABLE member_payment_reversals
    ADD CONSTRAINT fk_payment_reversals_withdrawn_by FOREIGN KEY (withdrawn_by) REFERENCES users (id);

ALTER TABLE member_payment_reversals DROP CONSTRAINT chk_payment_reversals_status;
ALTER TABLE member_payment_reversals
    ADD CONSTRAINT chk_payment_reversals_status CHECK (status IN ('REQUESTED', 'EXECUTED', 'DECLINED', 'WITHDRAWN'));

ALTER TABLE member_payment_reversals DROP CONSTRAINT chk_payment_reversals_shape;
ALTER TABLE member_payment_reversals
    ADD CONSTRAINT chk_payment_reversals_shape CHECK (
        (status = 'REQUESTED'
            AND decided_by IS NULL AND decided_at IS NULL AND withdrawn_by IS NULL AND withdrawn_at IS NULL
            AND open_key = payment_id AND executed_key IS NULL)
        OR (status = 'EXECUTED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND withdrawn_by IS NULL AND withdrawn_at IS NULL
            AND open_key IS NULL AND executed_key = payment_id)
        OR (status = 'DECLINED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_note IS NOT NULL
            AND withdrawn_by IS NULL AND withdrawn_at IS NULL
            AND open_key IS NULL AND executed_key IS NULL)
        OR (status = 'WITHDRAWN'
            AND withdrawn_by = requested_by AND withdrawn_at IS NOT NULL
            AND decided_by IS NULL AND decided_at IS NULL
            AND open_key IS NULL AND executed_key IS NULL)
    );
