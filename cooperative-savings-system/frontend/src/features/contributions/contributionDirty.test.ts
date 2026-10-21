import { describe, expect, it } from 'vitest'
import { contributionLineLock, isContributionLineDirty, type ContributionLineInput } from './contributionHelpers'

const original: ContributionLineInput = {
  paidAmountInput: '5000.0000',
  paymentDateInput: '',
  paymentReferenceInput: 'MOMO-1',
  notesInput: '',
}

describe('isContributionLineDirty', () => {
  it('is clean for identical values', () => {
    expect(isContributionLineDirty(original, { ...original })).toBe(false)
  })

  it('ignores equal amounts written differently, and blank vs zero', () => {
    expect(isContributionLineDirty(original, { ...original, paidAmountInput: '5000' })).toBe(false)
    expect(isContributionLineDirty(original, { ...original, paidAmountInput: ' 5000.00 ' })).toBe(false)
    const zero = { ...original, paidAmountInput: '0' }
    expect(isContributionLineDirty(zero, { ...zero, paidAmountInput: '' })).toBe(false)
    expect(isContributionLineDirty(zero, { ...zero, paidAmountInput: '0.0000' })).toBe(false)
  })

  it('ignores whitespace in optional text and an empty field vs a whitespace-only one', () => {
    expect(isContributionLineDirty(original, { ...original, paymentReferenceInput: '  MOMO-1 ' })).toBe(false)
    expect(isContributionLineDirty(original, { ...original, notesInput: '   ' })).toBe(false)
    expect(isContributionLineDirty(original, { ...original, paymentDateInput: ' ' })).toBe(false)
  })

  it('detects every real change', () => {
    expect(isContributionLineDirty(original, { ...original, paidAmountInput: '5000.5' })).toBe(true)
    expect(isContributionLineDirty(original, { ...original, paymentDateInput: '2026-05-01' })).toBe(true)
    expect(isContributionLineDirty(original, { ...original, paymentReferenceInput: 'MOMO-2' })).toBe(true)
    expect(isContributionLineDirty(original, { ...original, paymentReferenceInput: '' })).toBe(true)
    expect(isContributionLineDirty(original, { ...original, notesInput: 'late' })).toBe(true)
  })

  it('treats text that is not a valid amount as a change so it gets validated', () => {
    for (const bad of ['abc', '1e3', '-5', '1.23456']) {
      expect(isContributionLineDirty(original, { ...original, paidAmountInput: bad })).toBe(true)
    }
  })
})

describe('contributionLineLock', () => {
  it('locks waived and cancelled rows as terminal', () => {
    expect(contributionLineLock({ status: 'WAIVED' })).toBe('terminal')
    expect(contributionLineLock({ status: 'CANCELLED', reviewStatus: 'APPROVED' })).toBe('terminal')
  })

  it('locks rows whose member submission awaits review', () => {
    expect(contributionLineLock({ status: 'PENDING', reviewStatus: 'PENDING' })).toBe('review')
  })

  it('does not lock ordinary rows, including an unpaid (status PENDING) row with no review', () => {
    expect(contributionLineLock({ status: 'PENDING', reviewStatus: null })).toBeNull()
    expect(contributionLineLock({ status: 'PARTIALLY_PAID', reviewStatus: 'APPROVED' })).toBeNull()
    expect(contributionLineLock({ status: 'PAID' })).toBeNull()
    expect(contributionLineLock({ status: 'PENDING', reviewStatus: 'REJECTED' })).toBeNull()
  })
})
