import { describe, expect, it } from 'vitest'
import i18n from '@/i18n'
import { formatMoney } from '@/shared/utils/formatMoney'
import type { AuditTranslate } from './auditPresentation'
import {
  AUDITABLE_ACTIONS,
  buildAuditChangeRows,
  buildAuditDescription,
  buildAuditRecordLabel,
  displayAuditAction,
  displayAuditEntityType,
  displayAuditFieldLabel,
  formatAuditValue,
  isHiddenAuditField,
} from './auditPresentation'

const t: AuditTranslate = (key, options) => String(i18n.t(key, options))

describe('auditPresentation', () => {
  it('maps LOAN_REQUEST to Loan requested, not the raw enum', () => {
    expect(displayAuditAction('LOAN_REQUEST', t)).toBe('Loan requested')
    expect(displayAuditAction('LOAN_REQUEST', t)).not.toBe('LOAN_REQUEST')
    expect(displayAuditAction('SETTINGS_CHANGE', t)).toBe('Settings changed')
    expect(displayAuditAction('FINE_ISSUE', t)).toBe('Fine issued')
    expect(displayAuditAction('PASSWORD_CHANGE', t)).toBe('Password changed')
    expect(displayAuditAction('REPAY', t)).toBe('Loan repayment recorded')
  })

  it('covers every known AuditableAction with a translation', () => {
    for (const action of AUDITABLE_ACTIONS) {
      const label = displayAuditAction(action, t)
      expect(label).not.toBe(action)
      expect(label).not.toMatch(/^[A-Z0-9]+(_[A-Z0-9]+)+$/)
    }
  })

  it('humanizes unknown future actions instead of showing the token', () => {
    expect(displayAuditAction('FUTURE_UNKNOWN_ACTION', t)).toBe('Future unknown action')
  })

  it('maps LoanRepayment and other entity types to readable labels', () => {
    expect(displayAuditEntityType('LoanRepayment', t)).toBe('Loan repayment')
    expect(displayAuditEntityType('Loan', t)).toBe('Loan')
    expect(displayAuditEntityType('Fine', t)).toBe('Fine')
    expect(displayAuditEntityType('FineSettings', t)).toBe('Fine settings')
    expect(displayAuditEntityType('Cooperative', t)).toBe('Saving Scheme')
    expect(displayAuditEntityType('IncomeExpenseTransaction', t)).toBe('Income/expense')
    expect(displayAuditEntityType('StoredFile', t)).toBe('File')
  })

  it('falls back to readable text for unknown PascalCase entity types', () => {
    expect(displayAuditEntityType('WeirdEntityName', t)).toBe('Weird entity name')
  })

  it('maps field keys to user-friendly labels with camelCase fallback', () => {
    expect(displayAuditFieldLabel('approvedAmount', t)).toBe('Approved amount')
    expect(displayAuditFieldLabel('guaranteeMode', t)).toBe('Loan type')
    expect(displayAuditFieldLabel('totalAmount', t)).toBe('Total amount')
    expect(displayAuditFieldLabel('paymentDate', t)).toBe('Payment date')
    expect(displayAuditFieldLabel('shareCount', t)).toBe('Number of shares')
    expect(displayAuditFieldLabel('repaymentDateModel', t)).toBe('Repayment date model')
    expect(displayAuditFieldLabel('brandNewFieldName', t)).toBe('Brand new field name')
  })

  it('formats approvedAmount as money using the shared formatter', () => {
    const formatted = formatAuditValue('300000.0000', 'approvedAmount', t, 'Loan')
    const expected = formatMoney('300000.0000')
    expect(formatted).toBe(expected)
    expect(formatted).toContain('300,000')
  })

  it('humanizes and localizes PENDING and other status enums', () => {
    expect(formatAuditValue('PENDING', 'status', t, 'Loan')).toBe('Pending first approval')
    expect(formatAuditValue('AWAITING_SECOND_APPROVAL', 'status', t, 'Loan')).toBe(
      'Awaiting second approval',
    )
    expect(formatAuditValue('APPROVED', 'status', t, 'Loan')).toBe('Approved')
    expect(formatAuditValue('GUARANTOR', 'guaranteeMode', t, 'Loan')).toBe(
      'Loan with a guarantor (umwishingizi)',
    )
    expect(formatAuditValue('SAME_DAY_OF_MONTH', 'repaymentDateModel', t, 'LoanSettings')).toBe(
      'Same day as disbursement',
    )
  })

  it('renders booleans as Yes/No and null as an em dash', () => {
    expect(formatAuditValue(true, 'autoFinesEnabled', t)).toBe('Yes')
    expect(formatAuditValue(false, 'autoFinesEnabled', t)).toBe('No')
    expect(formatAuditValue(null, 'status', t)).toBe('—')
    expect(formatAuditValue(undefined, 'status', t)).toBe('—')
  })

  it('hides internal identifier keys and raw UUID values', () => {
    expect(isHiddenAuditField('id')).toBe(true)
    expect(isHiddenAuditField('userId')).toBe(true)
    expect(isHiddenAuditField('memberUserId')).toBe(true)
    expect(isHiddenAuditField('entityId')).toBe(true)
    expect(isHiddenAuditField('cooperativeId')).toBe(true)
    expect(isHiddenAuditField('loanId')).toBe(true)
    expect(isHiddenAuditField('issuedBy')).toBe(true)
    expect(isHiddenAuditField('bootstrap')).toBe(true)
    expect(isHiddenAuditField('version')).toBe(true)
    expect(isHiddenAuditField('deleted')).toBe(true)
    expect(isHiddenAuditField('failedLoginAttempts')).toBe(true)
    expect(isHiddenAuditField('userAgent')).toBe(true)
    expect(isHiddenAuditField('nationalId')).toBe(false)
    expect(formatAuditValue('889a4772-aaaa-bbbb-cccc-dddddddddddd', 'reference', t)).toBe('—')
  })

  it('hides password, token, secret, and hash-like keys', () => {
    expect(isHiddenAuditField('password')).toBe(true)
    expect(isHiddenAuditField('passwordHash')).toBe(true)
    expect(isHiddenAuditField('newPassword')).toBe(true)
    expect(isHiddenAuditField('refreshToken')).toBe(true)
    expect(isHiddenAuditField('apiSecret')).toBe(true)
    expect(isHiddenAuditField('sessionHash')).toBe(true)
  })

  it('turns previous/new JSON into structured change rows without internal IDs', () => {
    const view = buildAuditChangeRows(
      '{"status":"AWAITING_SECOND_APPROVAL","loanId":"889a4772-aaaa-bbbb-cccc-dddddddddddd"}',
      '{"status":"APPROVED","approvedAmount":"300000.0000","loanId":"889a4772-aaaa-bbbb-cccc-dddddddddddd","userId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}',
      t,
      'Loan',
    )
    expect(view.empty).toBe(false)
    expect(view.rows.map((row) => row.key)).toEqual(['status', 'approvedAmount'])
    expect(view.rows.find((row) => row.key === 'status')).toEqual({
      key: 'status',
      label: 'Status',
      previous: 'Awaiting second approval',
      next: 'Approved',
    })
    const amount = view.rows.find((row) => row.key === 'approvedAmount')
    expect(amount?.label).toBe('Approved amount')
    expect(amount?.previous).toBe('—')
    expect(amount?.next).toBe(formatMoney('300000.0000'))
    expect(view.rows.some((row) => /loanId|userId/i.test(row.key))).toBe(false)
  })

  it('does not crash on invalid JSON and shows a sanitized Details row', () => {
    expect(() => buildAuditChangeRows('not-json {', '{"ok":true}', t)).not.toThrow()
    const view = buildAuditChangeRows('not-json {', '{"autoFinesEnabled":true}', t)
    expect(view.empty).toBe(false)
    const details = view.rows.find((row) => row.key === '__details')
    expect(details?.label).toBe('Details')
    expect(details?.previous).toBe('not-json {')
    expect(details?.previous).not.toMatch(/^\s*\{/)
    expect(view.rows.find((row) => row.key === 'autoFinesEnabled')?.next).toBe('Yes')
  })

  it('reports no changes when both payloads are empty', () => {
    expect(buildAuditChangeRows(null, null, t).empty).toBe(true)
    expect(buildAuditChangeRows('', '', t).empty).toBe(true)
    expect(buildAuditChangeRows('{}', '{}', t).empty).toBe(true)
  })

  it('builds a deterministic description from actor, action, and record labels', () => {
    expect(
      buildAuditDescription(
        {
          action: 'REPAY',
          entityType: 'LoanRepayment',
          entityLabel: 'John Member',
          userName: 'Jane Doe',
          username: 'jane',
          userId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        },
        t,
      ),
    ).toBe('Jane Doe — Loan repayment recorded for John Member.')
    expect(
      buildAuditRecordLabel({ entityType: 'LoanRepayment', entityLabel: 'John Member' }, t),
    ).toBe('Loan repayment · John Member')
  })

  it('keeps filter/API action values as raw enums while labels are human-readable', () => {
    const apiAction = 'LOAN_REQUEST'
    expect(apiAction).toBe('LOAN_REQUEST')
    expect(displayAuditAction(apiAction, t)).toBe('Loan requested')
    const apiEntityType = 'Cooperative'
    expect(apiEntityType).toBe('Cooperative')
    expect(displayAuditEntityType(apiEntityType, t)).toBe('Saving Scheme')
  })
})
