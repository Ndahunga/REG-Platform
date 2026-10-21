import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import en from '@/i18n/locales/en.json'
import { parseContentDispositionFilename } from '@/shared/utils/download'
import {
  canCancelImport,
  canConfirmImport,
  defaultExportFilename,
  defaultReportFromDate,
  defaultReportToDate,
  isValidReportWhatsAppRecipient,
  importRowValidityColor,
  importRowValidityLabelKey,
  importStatusColor,
  importStatusLabelKey,
  reportSupportsMember,
  reportSupportsTransactionType,
  reportTypeLabelKey,
  validateReportTimeline,
} from './reportHelpers'

describe('parseContentDispositionFilename', () => {
  it('parses quoted filename', () => {
    expect(
      parseContentDispositionFilename(
        'attachment; filename="contributions-2026.xlsx"',
        'fallback.xlsx',
      ),
    ).toBe('contributions-2026.xlsx')
  })

  it('parses RFC 5987 filename*', () => {
    expect(
      parseContentDispositionFilename(
        "attachment; filename*=UTF-8''members%20report.xlsx",
        'fallback.xlsx',
      ),
    ).toBe('members report.xlsx')
  })

  it('returns fallback when header missing', () => {
    expect(parseContentDispositionFilename(undefined, 'report.xlsx')).toBe('report.xlsx')
  })
})

describe('importRowValidityColor', () => {
  it('maps valid/invalid to chip colors', () => {
    expect(importRowValidityColor(true)).toBe('success')
    expect(importRowValidityColor(false)).toBe('error')
  })
})

describe('importRowValidityLabelKey', () => {
  it('builds i18n keys', () => {
    expect(importRowValidityLabelKey(true)).toBe('reports.import.rowValid')
    expect(importRowValidityLabelKey(false)).toBe('reports.import.rowInvalid')
  })
})

describe('importStatusColor', () => {
  it('maps known statuses', () => {
    expect(importStatusColor('VALIDATED')).toBe('warning')
    expect(importStatusColor('CONFIRMED')).toBe('success')
    expect(importStatusColor('FAILED')).toBe('error')
  })
})

describe('importStatusLabelKey', () => {
  it('builds i18n key', () => {
    expect(importStatusLabelKey('CONFIRMED')).toBe('reports.import.status.CONFIRMED')
  })
})

describe('canConfirmImport', () => {
  it('requires admin and valid rows on preview statuses', () => {
    expect(canConfirmImport('VALIDATED', 3, true)).toBe(true)
    expect(canConfirmImport('VALIDATED', 0, true)).toBe(false)
    expect(canConfirmImport('VALIDATED', 3, false)).toBe(false)
    expect(canConfirmImport('CONFIRMED', 3, true)).toBe(false)
  })
})

describe('canCancelImport', () => {
  it('allows admin before confirm', () => {
    expect(canCancelImport('UPLOADED', true)).toBe(true)
    expect(canCancelImport('VALIDATED', true)).toBe(true)
    expect(canCancelImport('CONFIRMED', true)).toBe(false)
    expect(canCancelImport('VALIDATED', false)).toBe(false)
  })
})

describe('report filter helpers', () => {
  it('detects member and ledger filters', () => {
    expect(reportSupportsMember('LOANS')).toBe(true)
    expect(reportSupportsTransactionType('FINANCIAL_LEDGER')).toBe(true)
  })
})

describe('reportTypeLabelKey', () => {
  it('builds i18n key', () => {
    expect(reportTypeLabelKey('MEMBERS')).toBe('reports.types.MEMBERS')
  })
})

describe('defaultExportFilename', () => {
  it('slugifies report type as pdf', () => {
    expect(defaultExportFilename('SPECIAL_CONTRIBUTIONS')).toBe('special-contributions.pdf')
  })
})

describe('report export labels', () => {
  it('keeps Download PDF and adds Share via WhatsApp separately', () => {
    expect(en.reports.export.submit).toBe('Download PDF')
    expect(en.reports.whatsapp.share).toBe('Share via WhatsApp')
    expect(en.loans.schedule.downloadPdf).toBe('Download PDF')
    expect(en.loans.schedule.shareWhatsApp).toBe('Share via WhatsApp')
  })
})

describe('isValidReportWhatsAppRecipient', () => {
  it('accepts Rwandan mobiles and rejects others', () => {
    expect(isValidReportWhatsAppRecipient('0788123456')).toBe(true)
    expect(isValidReportWhatsAppRecipient('+250788123456')).toBe(true)
    expect(isValidReportWhatsAppRecipient('not-a-phone')).toBe(false)
    expect(isValidReportWhatsAppRecipient('')).toBe(false)
  })
})

describe('report timeline validation', () => {
  const today = dayjs('2026-08-20')

  it('defaults from start of year to today', () => {
    expect(defaultReportFromDate(today)).toBe('2026-01-01')
    expect(defaultReportToDate(today)).toBe('2026-08-20')
  })

  it('requires both dates', () => {
    expect(validateReportTimeline('', '', today)).toBe('required')
    expect(validateReportTimeline('2026-01-01', '', today)).toBe('required')
  })

  it('rejects future dates and inverted ranges', () => {
    expect(validateReportTimeline('2026-08-21', '2026-08-21', today)).toBe('futureFrom')
    expect(validateReportTimeline('2026-01-01', '2026-08-21', today)).toBe('futureTo')
    expect(validateReportTimeline('2026-08-20', '2026-01-01', today)).toBe('fromAfterTo')
  })

  it('accepts ranges longer than five years and dates before registration', () => {
    expect(validateReportTimeline('2020-01-01', '2026-01-02', today)).toBeNull()
    expect(validateReportTimeline('2022-01-01', '2026-08-20', today, '2024-01-15')).toBeNull()
  })

  it('accepts a valid past range', () => {
    expect(validateReportTimeline('2026-01-01', '2026-08-20', today)).toBeNull()
  })

  it('defaults from the start of the year even when registration is later', () => {
    expect(defaultReportFromDate(today, '2026-03-15')).toBe('2026-01-01')
  })
})
