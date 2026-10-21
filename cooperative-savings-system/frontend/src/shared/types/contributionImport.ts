export type ContributionImportStatus =
  | 'UPLOADED'
  | 'VALIDATED'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'FAILED'

export const CONTRIBUTION_IMPORT_STATUSES: ContributionImportStatus[] = [
  'UPLOADED',
  'VALIDATED',
  'CONFIRMED',
  'CANCELLED',
  'FAILED',
]

export interface ContributionImportRow {
  rowNumber: number
  username?: string | null
  memberName?: string | null
  amount?: string | number | null
  paymentDate?: string | null
  reference?: string | null
  notes?: string | null
  valid: boolean
  errors?: string[] | null
  errorMessages?: string[] | null
  memberUserId?: string | null
}

export interface ContributionImportPreview {
  importId: string
  id?: string
  year?: number
  month?: number
  rows: ContributionImportRow[]
  validCount: number
  invalidCount: number
  totalRows?: number
  status?: ContributionImportStatus | string
  originalFilename?: string | null
}

export interface ContributionImportSummary {
  id: string
  cooperativeId?: string
  year: number
  month: number
  originalFilename?: string | null
  storageKey?: string | null
  contentType?: string | null
  sizeBytes?: number | null
  status: ContributionImportStatus | string
  totalRows?: number | null
  validRows?: number | null
  invalidRows?: number | null
  uploadedBy?: string | null
  confirmedBy?: string | null
  confirmedAt?: string | null
  errorSummary?: string | null
  createdAt?: string
  updatedAt?: string
  version?: number
  rows?: ContributionImportRow[]
  validCount?: number
  invalidCount?: number
}

function normalizeErrors(row: ContributionImportRow): string[] {
  const fromErrors = row.errors ?? []
  const fromMessages = row.errorMessages ?? []
  return [...fromErrors, ...fromMessages].filter(Boolean).map(String)
}

export function mapContributionImportRow(raw: ContributionImportRow): ContributionImportRow {
  const errors = normalizeErrors(raw)
  return {
    ...raw,
    rowNumber: Number(raw.rowNumber),
    valid: Boolean(raw.valid),
    errors,
  }
}

export function mapContributionImportPreview(
  raw: ContributionImportPreview & { id?: string },
): ContributionImportPreview {
  const rows = (raw.rows ?? []).map(mapContributionImportRow)
  const validCount =
    raw.validCount ?? rows.filter((row) => row.valid).length
  const invalidCount =
    raw.invalidCount ?? rows.filter((row) => !row.valid).length
  return {
    ...raw,
    importId: String(raw.importId ?? raw.id ?? ''),
    rows,
    validCount,
    invalidCount,
    totalRows: raw.totalRows ?? rows.length,
  }
}

export function mapContributionImportSummary(raw: ContributionImportSummary): ContributionImportSummary {
  return {
    ...raw,
    id: String(raw.id),
    year: Number(raw.year),
    month: Number(raw.month),
    status: raw.status,
    rows: raw.rows?.map(mapContributionImportRow),
    validCount: raw.validCount ?? raw.validRows ?? undefined,
    invalidCount: raw.invalidCount ?? raw.invalidRows ?? undefined,
  }
}
