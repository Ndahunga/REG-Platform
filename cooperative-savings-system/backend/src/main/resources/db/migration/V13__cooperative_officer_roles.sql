-- Ikimina cooperative officer roles and maker-checker permissions.
-- President / Vice President lead the group. Secretary is records. Accountant is the ledger.
-- Loan Officer reviews loans. Money leaving the fund requires FUND_AUTHORIZE.

INSERT INTO roles (id, code, name, description) VALUES
    ('44444444-4444-4444-4444-444444444444', 'PRESIDENT', 'President', 'Chairs the group, enforces bylaws, co-signs major fund decisions, and may override in edge cases'),
    ('55555555-5555-5555-5555-555555555555', 'VICE_PRESIDENT', 'Vice President', 'Acting President with the same authority, and heads the loan committee'),
    ('66666666-6666-6666-6666-666666666666', 'SECRETARY', 'Secretary', 'Membership register, meeting records, and the audit trail of decisions'),
    ('77777777-7777-7777-7777-777777777777', 'ACCOUNTANT', 'Accountant / Treasurer', 'Records contributions, loans, repayments, and prepares financial summaries'),
    ('88888888-8888-8888-8888-888888888888', 'LOAN_OFFICER', 'Loan Officer', 'Reviews and approves or rejects loan applications against group bylaws');

INSERT INTO permissions (id, code, name, description) VALUES
    ('a0000001-0000-0000-0000-00000000001a', 'LOAN_APPROVE', 'Approve loans', 'Approve or reject loan applications (loan committee)'),
    ('a0000001-0000-0000-0000-00000000001b', 'FUND_AUTHORIZE', 'Authorize fund movements', 'Second sign-off on payouts, investments, and other money leaving the group fund');

INSERT INTO role_permissions (role_id, permission_id)
SELECT '11111111-1111-1111-1111-111111111111', id
FROM permissions
WHERE code IN ('LOAN_APPROVE', 'FUND_AUTHORIZE');

-- President and Vice President inherit every current cooperative-admin permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT '44444444-4444-4444-4444-444444444444', permission_id
FROM role_permissions
WHERE role_id = '22222222-2222-2222-2222-222222222222';

INSERT INTO role_permissions (role_id, permission_id)
SELECT '55555555-5555-5555-5555-555555555555', permission_id
FROM role_permissions
WHERE role_id = '22222222-2222-2222-2222-222222222222';

INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('44444444-4444-4444-4444-444444444444', 'a0000001-0000-0000-0000-00000000001a'),
    ('44444444-4444-4444-4444-444444444444', 'a0000001-0000-0000-0000-00000000001b'),
    ('55555555-5555-5555-5555-555555555555', 'a0000001-0000-0000-0000-00000000001a'),
    ('55555555-5555-5555-5555-555555555555', 'a0000001-0000-0000-0000-00000000001b'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000001a'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000001b');

-- Secretary: record-of-truth, not a financial writer.
INSERT INTO role_permissions (role_id, permission_id)
SELECT '66666666-6666-6666-6666-666666666666', id
FROM permissions
WHERE code IN (
    'USER_READ',
    'COOPERATIVE_READ',
    'MEMBERSHIP_MANAGE',
    'AUDIT_READ',
    'REPORT_READ',
    'FILE_MANAGE',
    'CONTRIBUTION_READ',
    'LOAN_READ',
    'FINE_READ',
    'SOCIAL_READ',
    'PAYOUT_READ'
);

-- Accountant / Treasurer: ledger recording. Cannot approve loans or co-sign fund movements.
INSERT INTO role_permissions (role_id, permission_id)
SELECT '77777777-7777-7777-7777-777777777777', id
FROM permissions
WHERE code IN (
    'USER_READ',
    'COOPERATIVE_READ',
    'CONTRIBUTION_READ',
    'CONTRIBUTION_WRITE',
    'LOAN_READ',
    'LOAN_WRITE',
    'LEDGER_READ',
    'REPORT_READ',
    'FILE_MANAGE',
    'FINE_READ',
    'FINE_WRITE',
    'SOCIAL_READ',
    'SOCIAL_WRITE',
    'INVESTMENT_READ',
    'INVESTMENT_WRITE',
    'INCOME_EXPENSE_READ',
    'INCOME_EXPENSE_WRITE',
    'PAYOUT_READ',
    'PAYOUT_WRITE'
);

-- Loan Officer: eligibility review and approval only. Disbursement stays with the Accountant.
INSERT INTO role_permissions (role_id, permission_id)
SELECT '88888888-8888-8888-8888-888888888888', id
FROM permissions
WHERE code IN (
    'USER_READ',
    'COOPERATIVE_READ',
    'CONTRIBUTION_READ',
    'LOAN_READ',
    'LOAN_APPROVE',
    'LEDGER_READ',
    'REPORT_READ',
    'FILE_MANAGE'
);

UPDATE cooperative_memberships
SET role_in_cooperative = 'PRESIDENT'
WHERE role_in_cooperative = 'COOPERATIVE_ADMIN';

INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, '44444444-4444-4444-4444-444444444444'
FROM user_roles ur
WHERE ur.role_id = '22222222-2222-2222-2222-222222222222'
ON CONFLICT DO NOTHING;

DELETE FROM user_roles
WHERE role_id = '22222222-2222-2222-2222-222222222222';

UPDATE roles
SET name = 'Cooperative Administrator (legacy)',
    description = 'Legacy alias for President. Existing accounts were migrated to PRESIDENT.'
WHERE code = 'COOPERATIVE_ADMIN';
