import { describe, expect, it } from 'vitest'
import {
  canCancelHistoricalImport,
  describeHistoricalImportError,
  isLegacyHistoricalImport,
  isValidOpeningFunds,
  isHistoricalImportReady,
  type HistoricalImportPreview,
} from './historicalImport'

function preview(overrides: Partial<HistoricalImportPreview> = {}): HistoricalImportPreview {
  return {
    importId: 'imp-1',
    status: 'READY',
    totalRows: 2,
    validRows: 2,
    invalidRows: 0,
    confirmAllowed: true,
    sheets: [],
    errors: [],
    ...overrides,
  }
}

describe('historical import helpers', () => {
  it('allows cancel only before confirm', () => {
    expect(canCancelHistoricalImport('READY')).toBe(true)
    expect(canCancelHistoricalImport('INVALID')).toBe(true)
    expect(canCancelHistoricalImport('CONFIRMED')).toBe(false)
    expect(canCancelHistoricalImport('FAILED')).toBe(false)
  })

  it('enables confirm only for a fully valid READY preview', () => {
    expect(isHistoricalImportReady(preview())).toBe(true)
    expect(isHistoricalImportReady(preview({ invalidRows: 1, confirmAllowed: false, status: 'INVALID' }))).toBe(
      false,
    )
    expect(isHistoricalImportReady(preview({ totalRows: 0, validRows: 0, confirmAllowed: false }))).toBe(false)
    expect(isHistoricalImportReady(preview({ reportReady: false }))).toBe(false)
    expect(isHistoricalImportReady(null)).toBe(false)
  })
})

describe('opening-balance migration helpers', () => {
  it('describes an error as sheet, row and column', () => {
    expect(
      describeHistoricalImportError({
        sheet: 'Loan Registration',
        rowNumber: 4,
        field: 'Outstanding Principal (RWF)',
        message: 'x',
      }),
    ).toBe('Loan Registration — Row 4 — Outstanding Principal (RWF)')
    expect(describeHistoricalImportError({ sheet: 'Share Capital Registration' })).toBe(
      'Share Capital Registration',
    )
  })

  it('recognises the legacy format and never marks a legacy or invalid preview ready', () => {
    expect(isLegacyHistoricalImport('LEGACY')).toBe(true)
    expect(isLegacyHistoricalImport('OPENING_BALANCE')).toBe(false)
    expect(isLegacyHistoricalImport(undefined)).toBe(false)
    expect(isHistoricalImportReady(preview({ status: 'INVALID' }))).toBe(false)
  })
})

describe('opening available funds input', () => {
  it('accepts non-negative amounts with up to two decimals only', () => {
    expect(isValidOpeningFunds('0')).toBe(true)
    expect(isValidOpeningFunds('800000')).toBe(true)
    expect(isValidOpeningFunds(' 800000.50 ')).toBe(true)
    expect(isValidOpeningFunds('')).toBe(false)
    expect(isValidOpeningFunds('-1')).toBe(false)
    expect(isValidOpeningFunds('800,000')).toBe(false)
    expect(isValidOpeningFunds('1.234')).toBe(false)
    expect(isValidOpeningFunds('abc')).toBe(false)
  })
})
