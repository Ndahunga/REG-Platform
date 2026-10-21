import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import { POSTING_ERROR_CODES } from './paymentHelpers'

type Tree = { [key: string]: string | Tree }

function flatten(node: Tree, prefix = ''): string[] {
  return Object.entries(node).flatMap(([key, value]) =>
    typeof value === 'string' ? [`${prefix}${key}`] : flatten(value, `${prefix}${key}.`),
  )
}

const here = join(__dirname)
const sources = [
  ...readdirSync(here)
    .filter((file) => /\.tsx?$/.test(file) && !/\.test\./.test(file))
    .map((file) => join(here, file)),
  join(__dirname, '..', '..', 'pages', 'PaymentsPage.tsx'),
  join(__dirname, '..', 'rotation', 'RotationEntitlementsPanel.tsx'),
  join(__dirname, '..', 'rotation', 'RotationPayoutsPanel.tsx'),
  join(__dirname, '..', 'historicalTransactions', 'HistoricalTransactionsPanel.tsx'),
  join(__dirname, '..', 'reports', 'ModelReportPanel.tsx'),
]

/** Static keys in t('...') calls plus the dynamic families used with template strings. */
function usedKeys(): Set<string> {
  const keys = new Set<string>()
  for (const file of sources) {
    const text = readFileSync(file, 'utf8')
    for (const match of text.matchAll(
      /\bt\(\s*'((?:payments|pages\.payments|nav\.payments|summary|rotation\.entitlements|rotation\.payouts|historicalTransactions|modelReport)[^']*)'/g,
    )) {
      keys.add(match[1])
    }
  }
  return keys
}

/** Every code the backend validator can report for a historical row. */
const ISSUE_CODES = [
  'UNSUPPORTED_ROW_TYPE', 'UNSUPPORTED_SETTING', 'MISSING_SETTING', 'USERNAME_REQUIRED', 'MEMBER_NOT_FOUND', 'DUPLICATE_IN_FILE',
  'INVALID_SHARE_COUNT', 'INVALID_AMOUNT', 'INVALID_DATE', 'DATE_IN_FUTURE', 'DATE_TOO_OLD', 'DATE_AFTER_HISTORY_END',
  'DATE_OVERLAPS_LIVE_RECORDS', 'CURRENCY_MISMATCH', 'OPERATING_MODEL_MISMATCH', 'DUPLICATE_SETTING', 'MEMBER_REQUIRED',
  'REFERENCE_TOO_LONG', 'REFERENCE_REQUIRED', 'DESCRIPTION_TOO_LONG', 'LOAN_REFERENCE_REQUIRED', 'LOAN_REFERENCE_NOT_APPLICABLE',
  'PERIOD_NOT_APPLICABLE', 'MODEL_NOT_APPLICABLE', 'INVALID_PERIOD', 'INVALID_MODEL', 'DUPLICATE_EXISTING_CONTRIBUTION',
  'LOAN_DISBURSEMENT_MISSING', 'LOAN_DISBURSED_TWICE', 'LOAN_MEMBER_MISMATCH', 'REPAYMENT_BEFORE_DISBURSEMENT', 'LOAN_OVERPAID',
  'LOAN_NOT_CLOSED', 'DUPLICATE_PREVIOUSLY_IMPORTED', 'NO_TRANSACTIONS',
]

const enKeys = new Set(flatten(en as Tree))
const rwKeys = new Set(flatten(rw as Tree))

describe('payments translations', () => {
  it('has English and Kinyarwanda for every key the payment screens use', () => {
    const missingEn = [...usedKeys()].filter((key) => !enKeys.has(key))
    const missingRw = [...usedKeys()].filter((key) => !rwKeys.has(key))
    expect(missingEn).toEqual([])
    expect(missingRw).toEqual([])
    expect(usedKeys().size).toBeGreaterThan(60)
  })

  it('keeps key parity between English and Kinyarwanda for payments, pages and navigation', () => {
    const scope = (keys: Set<string>) =>
      [...keys]
        .filter(
          (key) =>
            key.startsWith('payments.') ||
            key.startsWith('pages.payments.') ||
            key === 'nav.payments' ||
            key.startsWith('summary.') ||
            key.startsWith('historicalTransactions.') ||
            key.startsWith('modelReport.') ||
            key.startsWith('rotation.entitlements.') ||
            key.startsWith('rotation.payouts.') ||
            key === 'rotation.tabs.entitlements' ||
            key === 'rotation.tabs.payouts',
        )
        .sort()
    expect(scope(rwKeys)).toEqual(scope(enKeys))
  })

  it('translates the dynamic families: statuses, notices, methods, verification methods, attributions and error codes', () => {
    const families: Record<string, string[]> = {
      'payments.status': ['SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'POSTED', 'REJECTED', 'REVERSED'],
      'payments.notice': ['SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'POSTED', 'REJECTED', 'REVERSED'],
      'payments.method': ['MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'CASH', 'OTHER'],
      'payments.verification.methods': ['CASH_RECEIPT_CONFIRMED', 'MOBILE_MONEY_RECONCILED', 'BANK_RECONCILED', 'PROVIDER_CONFIRMED'],
      'payments.posting.attribution': ['ATTRIBUTED', 'ACCUMULATING', 'UNATTRIBUTED', 'NOT_APPLICABLE'],
      'payments.credit.purposes': ['ADVANCE_CONTRIBUTION', 'LOAN_PREPAYMENT', 'UNAPPLIED_CREDIT'],
      'payments.receiptAnalysis.reasons': [
        'RECEIPT_UNCLEAR',
        'AMOUNT_AMBIGUOUS',
        'AMOUNT_NOT_FOUND',
        'LOW_CONFIDENCE',
        'RECIPIENT_MISMATCH',
        'CURRENCY_MISMATCH',
        'AMOUNT_MISMATCH',
        'REFERENCE_MISMATCH',
        'UNREADABLE',
        'UNSUPPORTED_FILE',
      ],
      'payments.allocation.type': ['CONTRIBUTION', 'LOAN_REPAYMENT', 'MEMBER_CREDIT'],
      'payments.duty': ['PAYER', 'SUBMITTER', 'VERIFIER', 'EVIDENCE_RECORDER'],
      'payments.tabs': ['submit', 'mine', 'review', 'verified', 'posted', 'all', 'reconciliation', 'summary'],
      'payments.receipt.status': ['COMPLETED', 'FAILED', 'UNAVAILABLE', 'UNSUPPORTED'],
      'payments.receipt.failure': [
        'OCR_ENGINE_UNAVAILABLE',
        'PDF_NOT_SUPPORTED',
        'UNSUPPORTED_FORMAT',
        'CONTENT_TYPE_MISMATCH',
        'NO_TEXT_FOUND',
        'OCR_FAILED',
        'OCR_TIMEOUT',
        'PDF_UNREADABLE',
        'PDF_ENCRYPTED',
        'PDF_TOO_MANY_PAGES',
        'PDF_NO_TEXT',
        'PDF_TIMEOUT',
        'PDF_BUSY',
        'generic',
      ],
      'payments.receipt.field': ['reference', 'amount', 'currency', 'paidAt', 'sender', 'recipient', 'provider', 'status'],
      'payments.receipt.level': ['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'],
      'payments.receipt.discrepancy': [
        'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH',
        'REFERENCE_MISMATCH',
        'PROVIDER_MISMATCH',
        'DATE_MISMATCH',
        'RECEIPT_NOT_SUCCESSFUL',
        'DUPLICATE_RECEIPT',
        'LOW_CONFIDENCE',
        'AMOUNT_AMBIGUOUS',
      ],
      'payments.outcome.value': ['CONFIRMED', 'PENDING', 'FAILED', 'UNMATCHED', 'CONFLICTED'],
      'payments.outcome.source': [
        'MANUAL_CASH_CONFIRMATION',
        'BANK_STATEMENT_IMPORT',
        'MOBILE_MONEY_STATEMENT_IMPORT',
        'PROVIDER_CALLBACK',
        'RECEIPT_OCR',
        'REFERENCE_MATCH',
      ],
      'payments.outcome.reason': [
        'METHOD_MISMATCH',
        'CONFLICTING_EVIDENCE',
        'TRANSACTION_ALREADY_USED',
        'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH',
        'WRONG_RECIPIENT',
        'RECEIPT_AMOUNT_CONFLICT',
        'NO_REFERENCE',
        'NO_TRUSTED_RECORD',
        'AWAITING_CASH_CONFIRMATION',
        'PROVIDER_REPORTED_FAILURE',
        'PROVIDER_REPORTED_PENDING',
      ],
      'payments.reconcile.status': ['REQUESTED', 'APPROVED', 'DECLINED', 'WITHDRAWN'],
      'payments.reconcile.result': ['CREATED', 'UPDATED', 'DUPLICATE', 'CONFLICT', 'INVALID'],
      'payments.reconcile.providerStatus': ['SUCCESS', 'PENDING', 'FAILED'],
      'historicalTransactions.status': ['PREVIEWED', 'POSTED', 'CANCELLED'],
      'historicalTransactions.rules': ['sheets', 'noInvention', 'loans', 'overlap', 'unresolved', 'final'],
      'historicalTransactions.issue': ISSUE_CODES,
      'modelReport.recipientColumns': ['period', 'recipient', 'collected', 'unreconciled', 'distributed', 'outstanding'],
      'historicalTransactions.reconciliation': ['year', 'credits', 'debits', 'net'],
      'payments.reversal.status': ['REQUESTED', 'EXECUTED', 'DECLINED', 'WITHDRAWN'],
      'rotation.payouts.status': ['REQUESTED', 'APPROVED', 'DISBURSED', 'REJECTED', 'CANCELLED'],
      'summary.model': ['ACCUMULATING', 'ROTATING', 'HYBRID', 'NONE'],
      'payments.errors': [...POSTING_ERROR_CODES],
    }
    for (const [prefix, names] of Object.entries(families)) {
      for (const name of names) {
        expect(enKeys.has(`${prefix}.${name}`), `en ${prefix}.${name}`).toBe(true)
        expect(rwKeys.has(`${prefix}.${name}`), `rw ${prefix}.${name}`).toBe(true)
      }
    }
  })

  it('tells the approved unresolved-attribution story in both languages and offers no workaround wording', () => {
    const english = (en as unknown as { payments: { errors: Record<string, string> } }).payments.errors.ROTATION_ATTRIBUTION_UNRESOLVED
    expect(english).toBe(
      'The original contribution period’s rotation allocation or scheduled recipient cannot be established. The payment remains verified but has not been financially posted.',
    )
    const kinyarwanda = (rw as unknown as { payments: { errors: Record<string, string> } }).payments.errors.ROTATION_ATTRIBUTION_UNRESOLVED
    expect(kinyarwanda.length).toBeGreaterThan(40)
    expect(english).not.toMatch(/assign|force|ignore|guess/i)
  })
})
