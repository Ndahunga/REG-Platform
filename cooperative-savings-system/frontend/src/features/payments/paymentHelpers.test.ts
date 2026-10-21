import { describe, expect, it } from 'vitest'
import type { Contribution } from '@/shared/types/contribution'
import type { Loan } from '@/shared/types/loan'
import {
  POSTING_ERROR_CODES,
  dutyBlockers,
  errorKeyFor,
  fromUnits,
  holdsAuthorities,
  isOpenContribution,
  isRepayableLoan,
  isUnresolvedAttribution,
  requiredAuthorities,
  toUnits,
  validateSubmission,
  verificationMethodsFor,
  verificationNeedsReference,
  type AllocationDraft,
} from './paymentHelpers'

const contributionDraft = (key: string, month: number, amount: string, maxUnits: number | null = 20_000_000): AllocationDraft => ({
  key,
  type: 'CONTRIBUTION',
  amount,
  periodYear: 2026,
  periodMonth: month,
  maxUnits,
})
const loanDraft = (key: string, loanId: string, amount: string, maxUnits: number | null = 1_000_000_000): AllocationDraft => ({
  key,
  type: 'LOAN_REPAYMENT',
  amount,
  loanId,
  maxUnits,
})

describe('exact money arithmetic', () => {
  it('parses plain decimals and nothing else', () => {
    expect(toUnits('1000')).toBe(10_000_000)
    expect(toUnits('0.0001')).toBe(1)
    expect(toUnits('10.00001')).toBeNull()
    expect(toUnits('-5')).toBeNull()
    expect(toUnits('abc')).toBeNull()
    expect(toUnits('')).toBeNull()
    expect(fromUnits(toUnits('1234.5')!)).toBe('1234.5')
    expect(fromUnits(10_000_000)).toBe('1000')
  })
})

describe('validateSubmission', () => {
  it('accepts contributions, loans and mixed payments whose lines add up to the total', () => {
    expect(validateSubmission('2000', [contributionDraft('a', 1, '2000')])).toEqual([])
    expect(validateSubmission('5000', [loanDraft('a', 'L1', '5000')])).toEqual([])
    expect(
      validateSubmission('6500', [contributionDraft('a', 1, '2000'), contributionDraft('b', 2, '1500'), loanDraft('c', 'L1', '3000')]),
    ).toEqual([])
    expect(validateSubmission('3000', [loanDraft('a', 'L1', '1000'), loanDraft('b', 'L2', '2000')])).toEqual([])
  })

  it('requires at least one allocation and a valid total', () => {
    expect(validateSubmission('1000', []).map((p) => p.code)).toContain('NO_ALLOCATIONS')
    expect(validateSubmission('', [contributionDraft('a', 1, '1000')]).map((p) => p.code)).toContain('INVALID_TOTAL')
    expect(validateSubmission('0', [contributionDraft('a', 1, '1000')]).map((p) => p.code)).toContain('INVALID_TOTAL')
  })

  it('flags invalid line amounts, overpayment and duplicate targets', () => {
    expect(validateSubmission('100', [contributionDraft('a', 1, '0')]).map((p) => p.code)).toContain('INVALID_ALLOCATION_AMOUNT')
    expect(validateSubmission('100', [contributionDraft('a', 1, '1.00001')]).map((p) => p.code)).toContain('INVALID_ALLOCATION_AMOUNT')
    const over = validateSubmission('3000', [contributionDraft('a', 1, '3000', 20_000_000)])
    expect(over.map((p) => p.code)).toContain('EXCEEDS_OUTSTANDING')
    const duplicate = validateSubmission('2000', [contributionDraft('a', 1, '1000'), contributionDraft('b', 1, '1000')])
    expect(duplicate.map((p) => p.code)).toContain('DUPLICATE_TARGET')
    const loans = validateSubmission('2000', [loanDraft('a', 'L1', '1000'), loanDraft('b', 'L1', '1000')])
    expect(loans.map((p) => p.code)).toContain('DUPLICATE_TARGET')
  })

  it('does not apply an outstanding limit when none is known', () => {
    expect(validateSubmission('9999999', [contributionDraft('a', 1, '9999999', null)])).toEqual([])
  })

  it('reports a total mismatch with both sums', () => {
    const problems = validateSubmission('3000', [contributionDraft('a', 1, '1000'), loanDraft('b', 'L1', '1999')])
    const mismatch = problems.find((p) => p.code === 'TOTAL_MISMATCH')
    expect(mismatch).toEqual({ code: 'TOTAL_MISMATCH', allocatedUnits: 29_990_000, totalUnits: 30_000_000 })
  })
})

describe('obligation filters', () => {
  const contribution = (overrides: Partial<Contribution>): Contribution =>
    ({ id: 'c', memberUserId: 'm', year: 2026, month: 1, expectedAmount: '2000', paidAmount: '500', outstandingAmount: '1500', status: 'PARTIALLY_PAID', ...overrides }) as Contribution

  it('only lists contribution periods that still need money and are not waived or cancelled', () => {
    expect(isOpenContribution(contribution({}))).toBe(true)
    expect(isOpenContribution(contribution({ paidAmount: '2000' }))).toBe(false)
    expect(isOpenContribution(contribution({ status: 'WAIVED' }))).toBe(false)
    expect(isOpenContribution(contribution({ status: 'CANCELLED' }))).toBe(false)
  })

  it('only lists active or overdue loans that still owe something', () => {
    const loan = (overrides: Partial<Loan>): Loan =>
      ({ id: 'l', memberUserId: 'm', status: 'ACTIVE', outstandingPrincipal: '1000', outstandingInterest: '0', outstandingPenalty: '0', ...overrides }) as Loan
    expect(isRepayableLoan(loan({}))).toBe(true)
    expect(isRepayableLoan(loan({ status: 'OVERDUE' }))).toBe(true)
    expect(isRepayableLoan(loan({ status: 'CLOSED' }))).toBe(false)
    expect(isRepayableLoan(loan({ outstandingPrincipal: '0' }))).toBe(false)
  })
})

describe('permissions and separation of duties', () => {
  it('requires the write authority of every allocation type', () => {
    expect(requiredAuthorities(['CONTRIBUTION']).sort()).toEqual(['CONTRIBUTION_WRITE'])
    expect(requiredAuthorities(['LOAN_REPAYMENT'])).toEqual(['LOAN_WRITE'])
    expect(requiredAuthorities(['CONTRIBUTION', 'LOAN_REPAYMENT']).sort()).toEqual(['CONTRIBUTION_WRITE', 'LOAN_WRITE'])
    const contributionOnly = { roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_WRITE'] }
    expect(holdsAuthorities(contributionOnly, ['CONTRIBUTION'])).toBe(true)
    expect(holdsAuthorities(contributionOnly, ['CONTRIBUTION', 'LOAN_REPAYMENT'])).toBe(false)
  })

  const payment = { memberUserId: 'payer', submittedBy: 'submitter', verifiedBy: 'verifier' }

  it('never lets the payer or submitter verify or post', () => {
    expect(dutyBlockers('verify', 'payer', payment)).toEqual(['PAYER'])
    expect(dutyBlockers('verify', 'submitter', payment)).toEqual(['SUBMITTER'])
    expect(dutyBlockers('post', 'payer', payment)).toContain('PAYER')
    expect(dutyBlockers('post', 'submitter', payment)).toContain('SUBMITTER')
  })

  it('keeps the poster different from the verifier and the evidence recorder', () => {
    expect(dutyBlockers('post', 'verifier', payment)).toEqual(['VERIFIER'])
    expect(dutyBlockers('post', 'recorder', payment, { verifiedBy: 'verifier', evidenceCompletedBy: 'recorder' })).toEqual([
      'EVIDENCE_RECORDER',
    ])
    expect(dutyBlockers('post', 'poster', payment, { verifiedBy: 'verifier', evidenceCompletedBy: 'recorder' })).toEqual([])
    // the verifier may of course verify; only posting is restricted
    expect(dutyBlockers('verify', 'verifier', payment)).toEqual([])
  })
})

describe('verification methods', () => {
  it('only offers methods that fit how the payment was made, and only cash may omit the reference', () => {
    expect(verificationMethodsFor('CASH')).toEqual(['CASH_RECEIPT_CONFIRMED'])
    expect(verificationMethodsFor('MTN_MOMO')).toEqual(['MOBILE_MONEY_RECONCILED', 'PROVIDER_CONFIRMED'])
    expect(verificationMethodsFor('BANK_TRANSFER')).toEqual(['BANK_RECONCILED', 'PROVIDER_CONFIRMED'])
    expect(verificationNeedsReference('CASH_RECEIPT_CONFIRMED')).toBe(false)
    expect(verificationNeedsReference('BANK_RECONCILED')).toBe(true)
  })
})

describe('backend error codes', () => {
  it('maps only the codes the backend really returns', () => {
    for (const code of POSTING_ERROR_CODES) {
      expect(errorKeyFor(code)).toBe(`payments.errors.${code}`)
    }
    expect(errorKeyFor('SOMETHING_INVENTED')).toBeNull()
    expect(errorKeyFor(null)).toBeNull()
  })

  it('recognises the unresolved rotation attribution failure', () => {
    expect(isUnresolvedAttribution('ROTATION_ATTRIBUTION_UNRESOLVED')).toBe(true)
    expect(isUnresolvedAttribution('OBLIGATION_NOT_FOUND')).toBe(false)
  })
})
