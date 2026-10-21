export type HistoricalImportStatus =
  | 'UPLOADED'
  | 'VALIDATING'
  | 'READY'
  | 'INVALID'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'FAILED'

/** OPENING_BALANCE = the cutover migration; LEGACY = read-only history of the retired 16-sheet import. */
export type HistoricalImportFormat = 'OPENING_BALANCE' | 'LEGACY'

export type MoneyValue = string | number | null | undefined

export interface HistoricalImportError {
  sheet?: string | null
  rowNumber?: number | null
  field?: string | null
  code?: string | null
  message?: string | null
}

export interface HistoricalImportSheetSummary {
  sheet: string
  totalRows: number
  validRows: number
  invalidRows: number
}

export interface HistoricalYearSummary {
  year: number
  members?: number
  contributions?: number
  specialContributions?: number
  socialContributions?: number
  socialDisbursements?: number
  loans?: number
  repayments?: number
  fines?: number
  finePayments?: number
  investments?: number
  investmentReturns?: number
  income?: number
  expenses?: number
  payouts?: number
}

/** Legacy (retired 16-sheet) imports only. */
export interface HistoricalReconciliationSummary {
  currentAvailableFund?: string | number | null
  projectedCredits?: string | number | null
  projectedDebits?: string | number | null
  projectedOutstandingLoanPrincipal?: string | number | null
  projectedAvailableFund?: string | number | null
  projectedSocialContributions?: string | number | null
  projectedSocialDisbursements?: string | number | null
  projectedSocialBalance?: string | number | null
  projectedPayouts?: string | number | null
  blocked?: boolean
  warnings?: string[]
  errors?: string[]
  yearSummaries?: HistoricalYearSummary[]
}

export interface HistoricalMigrationMembers {
  totalRows: number
  validRows: number
  invalidRows: number
  totalOpeningContributions: MoneyValue
}

export interface HistoricalMigrationShares {
  totalRows: number
  validRows: number
  invalidRows: number
  totalOpeningShares: number
  totalOpeningShareCapital: MoneyValue
}

export interface HistoricalMigrationLoans {
  totalRows: number
  validRows: number
  invalidRows: number
  endOfTermCount: number
  monthlyCount: number
  totalOriginalPrincipal: MoneyValue
  totalOutstandingPrincipal: MoneyValue
  totalAmountPaid: MoneyValue
}

export interface HistoricalImportConfirm {
  importId: string
  status: HistoricalImportStatus | string
  cutoverDate?: string | null
  openingAvailableFunds?: MoneyValue
  membersImported: number
  memberOpeningBalancesImported: number
  shareOpeningBalancesImported: number
  loansImported: number
  loanInstallmentsCreated: number
  totalHistoricalContributions: MoneyValue
  totalHistoricalShares: number
  totalHistoricalShareCapital: MoneyValue
  totalOriginalLoanAmount: MoneyValue
  totalOutstandingLoanPrincipal: MoneyValue
  totalHistoricalLoanPayments: MoneyValue
}

export interface HistoricalMigrationSummary {
  cutoverDate?: string | null
  /** Explicit cash available at the end of the cutover date. */
  openingAvailableFunds?: MoneyValue
  members?: HistoricalMigrationMembers | null
  shareCapital?: HistoricalMigrationShares | null
  loans?: HistoricalMigrationLoans | null
  result?: HistoricalImportConfirm | null
}

export interface HistoricalImportPreview {
  importId: string
  status: HistoricalImportStatus | string
  format?: HistoricalImportFormat | string | null
  /** OPENING_BALANCE (members and share capital) or OPENING_LOANS (the separate loan workbook). */
  kind?: 'OPENING_BALANCE' | 'OPENING_LOANS' | null
  originalFilename?: string | null
  fileHash?: string | null
  cutoverDate?: string | null
  totalRows: number
  validRows: number
  invalidRows: number
  confirmAllowed: boolean
  reportReady?: boolean
  migration?: HistoricalMigrationSummary | null
  sheets: HistoricalImportSheetSummary[]
  errors: HistoricalImportError[]
  /** Legacy imports only. */
  yearSummaries?: HistoricalYearSummary[]
  /** Legacy imports only. */
  reconciliation?: HistoricalReconciliationSummary | null
  errorSummary?: string | null
}

export interface HistoricalImportSummary {
  id: string
  cooperativeId: string
  originalFilename?: string | null
  fileHash?: string | null
  status: HistoricalImportStatus | string
  format?: HistoricalImportFormat | string | null
  /** OPENING_BALANCE (members and share capital) or OPENING_LOANS (the separate loan workbook). */
  kind?: 'OPENING_BALANCE' | 'OPENING_LOANS' | null
  cutoverDate?: string | null
  totalRows: number
  validRows: number
  invalidRows: number
  uploadedBy?: string | null
  createdAt?: string | null
  confirmedAt?: string | null
  cancelledAt?: string | null
  errorSummary?: string | null
}

export function canCancelHistoricalImport(status?: string | null): boolean {
  return status === 'UPLOADED' || status === 'VALIDATING' || status === 'READY' || status === 'INVALID'
}

export function isLegacyHistoricalImport(format?: string | null): boolean {
  return format === 'LEGACY'
}

export function isHistoricalImportReady(preview: HistoricalImportPreview | null): boolean {
  if (!preview) return false
  return (
    preview.confirmAllowed &&
    preview.reportReady !== false &&
    preview.invalidRows === 0 &&
    preview.validRows > 0 &&
    preview.status === 'READY'
  )
}

/** Short label of one validation error: `Sheet — Row 5 — Column`, as shown to the user. */
export function describeHistoricalImportError(error: HistoricalImportError): string {
  const parts: string[] = []
  if (error.sheet) parts.push(error.sheet)
  if (error.rowNumber != null) parts.push(`Row ${error.rowNumber}`)
  if (error.field) parts.push(error.field)
  return parts.join(' — ')
}

/** Non-negative decimal with at most two fraction digits; thousands separators are not accepted. */
export function isValidOpeningFunds(value: string): boolean {
  return /^\d+(\.\d{1,2})?$/.test(value.trim())
}
