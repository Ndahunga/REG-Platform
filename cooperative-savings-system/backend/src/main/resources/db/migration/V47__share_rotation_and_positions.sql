-- Share-based rotation, exact hybrid split and opening rotation positions.
--
-- 1. Exact split by amount. A hybrid scheme states its split in money (e.g. 50,000 rotated of 70,000 per share).
--    Basis points cannot hold 50/70 exactly (7142.857...), so 70,000 x 7143 / 10000 = 50,001 would over-credit the
--    rotation by 1 RWF per member-month. When rotating_unit_amount is set, the split of any amount is
--    amount x rotating_unit_amount / unit_amount (HALF_UP to whole RWF), which lands exactly on 50,000 for a full
--    payment. rotating_bps stays as the rounded display value and for legacy rows.
ALTER TABLE rotation_configurations ADD COLUMN rotating_unit_amount NUMERIC(19,4);
ALTER TABLE rotation_configurations ADD CONSTRAINT chk_rotation_configurations_unit_amount
    CHECK (rotating_unit_amount IS NULL OR rotating_unit_amount > 0);

ALTER TABLE contributions ADD COLUMN rotating_unit_snapshot NUMERIC(19,4);
ALTER TABLE contributions ADD CONSTRAINT chk_contributions_rotating_unit_snapshot
    CHECK (rotating_unit_snapshot IS NULL OR rotating_unit_snapshot > 0);

-- 2. Share-based cycles. PERIOD_COLLECTION (existing): one recipient per month receives what that month collected.
--    FIXED_PER_SHARE (new): every share has one turn worth payout_per_share; a month can hold several turns (several
--    members, or several shares of one member); payouts are funded from the scheme's rotation pool.
ALTER TABLE rotation_cycles ADD COLUMN payout_mode VARCHAR(24) NOT NULL DEFAULT 'PERIOD_COLLECTION';
ALTER TABLE rotation_cycles ADD COLUMN payout_per_share NUMERIC(19,4);
ALTER TABLE rotation_cycles ADD COLUMN turn_mode VARCHAR(24);
ALTER TABLE rotation_cycles ADD CONSTRAINT chk_rotation_cycles_payout_mode
    CHECK (payout_mode IN ('PERIOD_COLLECTION', 'FIXED_PER_SHARE'));
ALTER TABLE rotation_cycles ADD CONSTRAINT chk_rotation_cycles_fixed_payout
    CHECK (payout_mode <> 'FIXED_PER_SHARE' OR (payout_per_share IS NOT NULL AND payout_per_share > 0));
ALTER TABLE rotation_cycles ADD CONSTRAINT chk_rotation_cycles_turn_mode
    CHECK (turn_mode IS NULL OR turn_mode IN ('ONE_SHARE_PER_TURN', 'ALL_SHARES_TOGETHER', 'GROUPED_BY_MONTH'));

-- A member may now hold turns in several months of a share-based cycle (one per share). The service still refuses a
-- repeated recipient in a PERIOD_COLLECTION cycle.
ALTER TABLE rotation_periods DROP CONSTRAINT uq_rotation_periods_recipient;

CREATE TABLE rotation_turns (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    cycle_id                UUID            NOT NULL,
    rotation_period_id      UUID            NOT NULL,
    member_user_id          UUID            NOT NULL,
    share_count             INT             NOT NULL,
    payout_amount           NUMERIC(19,4)   NOT NULL,
    -- Paid before the scheme moved to OuWealth (migration). Never a ledger entry: the cash left before the cutover.
    opening_received_amount NUMERIC(19,4)   NOT NULL DEFAULT 0,
    opening_received_date   DATE,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_rotation_turns_cycle FOREIGN KEY (cycle_id, cooperative_id)
        REFERENCES rotation_cycles (id, cooperative_id),
    CONSTRAINT fk_rotation_turns_period FOREIGN KEY (rotation_period_id, cooperative_id)
        REFERENCES rotation_periods (id, cooperative_id),
    CONSTRAINT fk_rotation_turns_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT uq_rotation_turns_period_member UNIQUE (rotation_period_id, member_user_id),
    CONSTRAINT uq_rotation_turns_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT chk_rotation_turns_shares CHECK (share_count BETWEEN 1 AND 1000),
    CONSTRAINT chk_rotation_turns_amount CHECK (payout_amount > 0),
    CONSTRAINT chk_rotation_turns_opening CHECK (
        opening_received_amount >= 0
        AND opening_received_amount <= payout_amount
        AND (opening_received_amount = 0 OR opening_received_date IS NOT NULL)
    )
);
CREATE INDEX idx_rotation_turns_cycle ON rotation_turns (cooperative_id, cycle_id);
CREATE INDEX idx_rotation_turns_member ON rotation_turns (cooperative_id, member_user_id);

-- A payout of a share-based cycle names its turn; its open key is the turn (so two recipients of one month can each
-- have an open payout), while a period-collection payout keeps the period as its open key.
ALTER TABLE rotation_payouts ADD COLUMN rotation_turn_id UUID;
ALTER TABLE rotation_payouts ADD CONSTRAINT fk_rotation_payouts_turn FOREIGN KEY (rotation_turn_id, cooperative_id)
    REFERENCES rotation_turns (id, cooperative_id);
ALTER TABLE rotation_payouts DROP CONSTRAINT chk_rotation_payouts_open_key;
ALTER TABLE rotation_payouts ADD CONSTRAINT chk_rotation_payouts_open_key CHECK (
    (status IN ('REQUESTED', 'APPROVED') AND open_key = COALESCE(rotation_turn_id, rotation_period_id))
    OR (status NOT IN ('REQUESTED', 'APPROVED') AND open_key IS NULL)
);
CREATE INDEX idx_rotation_payouts_turn ON rotation_payouts (cooperative_id, rotation_turn_id, status);

-- A turn payout is netted only by the recipient's unpaid contributions of months already due (never by loans or
-- fines). The netted part never leaves as cash: at disbursement it is applied to those contributions, oldest first,
-- and each application is recorded here. It counts as verified money (it is the scheme's own payout, already approved).
ALTER TABLE rotation_payouts ADD COLUMN arrears_netted NUMERIC(19,4) NOT NULL DEFAULT 0;
ALTER TABLE rotation_payouts ADD CONSTRAINT chk_rotation_payouts_arrears
    CHECK (arrears_netted >= 0 AND arrears_netted <= amount);

CREATE TABLE rotation_payout_offsets (
    id              UUID            PRIMARY KEY,
    cooperative_id  UUID            NOT NULL,
    payout_id       UUID            NOT NULL REFERENCES rotation_payouts (id),
    contribution_id UUID            NOT NULL REFERENCES contributions (id),
    rotation_period_id UUID,
    amount          NUMERIC(19,4)   NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_rotation_payout_offsets UNIQUE (payout_id, contribution_id)
);
CREATE INDEX idx_rotation_payout_offsets_coop ON rotation_payout_offsets (cooperative_id, rotation_period_id);

-- 3. Opening rotation position carried in by a migration: rotated money the member had paid into the current cycle
--    before the cutover, and payouts they had already received. Read by the member position (statement) service.
ALTER TABLE member_opening_balances ADD COLUMN opening_rotation_paid NUMERIC(19,4) NOT NULL DEFAULT 0;
ALTER TABLE member_opening_balances ADD COLUMN opening_rotation_received NUMERIC(19,4) NOT NULL DEFAULT 0;
ALTER TABLE member_opening_balances ADD CONSTRAINT chk_member_opening_rotation_nonneg
    CHECK (opening_rotation_paid >= 0 AND opening_rotation_received >= 0);
