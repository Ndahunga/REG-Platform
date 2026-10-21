-- Member self-submissions, two-step loan approval, and immutable approval history.
-- Compatible with PostgreSQL (production) and H2 PostgreSQL mode (tests).

ALTER TABLE loans DROP CONSTRAINT chk_loans_status;
ALTER TABLE loans ADD CONSTRAINT chk_loans_status CHECK (status IN (
    'PENDING',
    'AWAITING_SECOND_APPROVAL',
    'APPROVED',
    'ACTIVE',
    'OVERDUE',
    'REJECTED',
    'CLOSED',
    'WRITTEN_OFF'
));

ALTER TABLE loans ADD COLUMN application_snapshot JSONB;
ALTER TABLE loans ADD COLUMN first_approved_by UUID;
ALTER TABLE loans ADD COLUMN first_approved_at TIMESTAMPTZ;
ALTER TABLE loans ADD COLUMN first_approver_role VARCHAR(64);
ALTER TABLE loans ADD CONSTRAINT fk_loans_first_approved_by FOREIGN KEY (first_approved_by) REFERENCES users (id);

CREATE TABLE approval_events (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    entity_type         VARCHAR(64)     NOT NULL,
    entity_id           UUID            NOT NULL,
    actor_user_id       UUID            NOT NULL,
    actor_name          VARCHAR(256)    NOT NULL,
    actor_role          VARCHAR(64)     NOT NULL,
    action              VARCHAR(32)     NOT NULL,
    previous_status     VARCHAR(64),
    new_status          VARCHAR(64),
    comment             VARCHAR(2000),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_approval_events_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_approval_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT chk_approval_events_action CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_approval_events_entity
    ON approval_events (cooperative_id, entity_type, entity_id, created_at);

ALTER TABLE contributions ADD COLUMN submitted_amount NUMERIC(19, 4);
ALTER TABLE contributions ADD COLUMN evidence_file_key VARCHAR(512);
ALTER TABLE contributions ADD COLUMN submitted_by UUID;
ALTER TABLE contributions ADD COLUMN submitted_at TIMESTAMPTZ;
ALTER TABLE contributions ADD COLUMN reviewed_by UUID;
ALTER TABLE contributions ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE contributions ADD COLUMN review_status VARCHAR(32);
ALTER TABLE contributions ADD COLUMN rejection_reason VARCHAR(2000);

ALTER TABLE contributions ADD CONSTRAINT fk_contributions_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id);
ALTER TABLE contributions ADD CONSTRAINT fk_contributions_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id);
ALTER TABLE contributions ADD CONSTRAINT chk_contributions_review_status CHECK (
    review_status IS NULL OR review_status IN ('PENDING', 'APPROVED', 'REJECTED')
);

CREATE INDEX idx_contributions_pending_review
    ON contributions (cooperative_id, review_status);
