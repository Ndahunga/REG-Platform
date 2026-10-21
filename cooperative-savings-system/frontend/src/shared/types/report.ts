export const STAFF_PRIMARY_REPORTS = [
  'CONTRIBUTIONS',
  'INVESTMENTS',
  'FULL_FINANCIAL',
] as const

export const MEMBER_PRIMARY_REPORTS = ['CONTRIBUTIONS', 'LOANS', 'FINES'] as const

export const REPORT_TYPES = [
  'MEMBERS',
  'CONTRIBUTIONS',
  'SPECIAL_CONTRIBUTIONS',
  'LOANS',
  'REPAYMENTS',
  'FINES',
  'FINE_PAYMENTS',
  'SOCIAL_FUND',
  'INVESTMENTS',
  'INCOME',
  'EXPENSES',
  'PAYOUTS',
  'FINANCIAL_LEDGER',
  'AUDIT_LOGS',
  'FULL_FINANCIAL',
] as const

export type ReportType = (typeof REPORT_TYPES)[number]

export interface ReportTypeInfo {
  type: ReportType | string
  code?: string
  label?: string
  name?: string
  description?: string
  supportsFromDate?: boolean
  supportsToDate?: boolean
  supportsMember?: boolean
  supportsStatus?: boolean
  supportsTransactionType?: boolean
  selfScoped?: boolean
}

export interface ReportExportRequest {
  reportType: ReportType | string
  fromDate?: string | null
  toDate?: string | null
  memberUserId?: string | null
  status?: string | null
  transactionType?: string | null
}

/** Public status only — never includes tokens or phone-number IDs. */
export interface ReportWhatsAppStatus {
  configured: boolean
}

export interface ReportWhatsAppShareRequest extends ReportExportRequest {
  recipientPhone: string
}

export interface ReportWhatsAppShareResult {
  sent: boolean
  recipient: string
  filename: string
}

export function mapReportTypeInfo(raw: ReportTypeInfo | string): ReportTypeInfo {
  if (typeof raw === 'string') {
    return { type: raw, label: raw }
  }
  const type = String(raw.type ?? raw.code ?? raw.name ?? '')
  return {
    ...raw,
    type,
    label: raw.label ?? raw.name ?? type,
    selfScoped: Boolean(raw.selfScoped),
  }
}
