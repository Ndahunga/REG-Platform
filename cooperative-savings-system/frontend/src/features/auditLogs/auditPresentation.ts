import dayjs from 'dayjs'
import { auditEntityLabel, auditUserLabel } from '@/features/auditLogs/auditHelpers'
import type { AuditLog } from '@/shared/types/auditLog'
import { formatMoney } from '@/shared/utils/formatMoney'

export type AuditTranslate = (key: string, options?: Record<string, unknown>) => string

export const MISSING_AUDIT_VALUE = '—'

/** All `AuditableAction` enum names currently stored by the backend. */
export const AUDITABLE_ACTIONS = [
  'LOGIN',
  'LOGIN_SUCCESS',
  'LOGOUT',
  'LOGIN_FAILED',
  'LOGIN_FAILURE',
  'PASSWORD_CHANGE',
  'PASSWORD_RESET',
  'PASSWORD_RESET_REQUEST',
  'PASSWORD_RESET_CONFIRM',
  'TOKEN_REFRESH',
  'CREATE',
  'UPDATE',
  'DELETE',
  'SOFT_DELETE',
  'RESTORE',
  'APPROVE',
  'REJECT',
  'DISBURSE',
  'REPAY',
  'IMPORT',
  'EXPORT',
  'SETTINGS_CHANGE',
  'ROLE_ASSIGN',
  'ROLE_REVOKE',
  'STATUS_CHANGE',
  'COOPERATIVE_CREATE',
  'COOPERATIVE_UPDATE',
  'COOPERATIVE_STATUS_CHANGE',
  'COOPERATIVE_ONBOARDING_CHANGE',
  'MEMBER_REGISTER',
  'MEMBER_UPDATE',
  'MEMBER_STATUS_CHANGE',
  'FILE_UPLOAD',
  'FILE_DELETE',
  'CONTRIBUTION_RECORD',
  'CONTRIBUTION_UPDATE',
  'CONTRIBUTION_BATCH',
  'CONTRIBUTION_SUBMIT',
  'CONTRIBUTION_APPROVE',
  'CONTRIBUTION_REJECT',
  'SPECIAL_CAMPAIGN_CREATE',
  'SPECIAL_CONTRIBUTION_SUBMIT',
  'SPECIAL_CONTRIBUTION_APPROVE',
  'SPECIAL_CONTRIBUTION_REJECT',
  'SHARE_PURCHASE_SUBMIT',
  'SHARE_PURCHASE_APPROVE',
  'SHARE_PURCHASE_REJECT',
  'LOAN_REQUEST',
  'LOAN_WRITE_OFF',
  'FINE_ISSUE',
  'FINE_UPDATE',
  'FINE_DELETE',
  'FINE_SETTINGS_CHANGE',
  'GUARANTOR_REQUEST',
  'GUARANTOR_ACCEPT',
  'GUARANTOR_REJECT',
  'FINE_PAYMENT_SUBMIT',
  'FINE_PAYMENT_APPROVE',
  'FINE_PAYMENT_REJECT',
  'FINE_WAIVE',
  'SOCIAL_CONTRIBUTION_SUBMIT',
  'SOCIAL_CONTRIBUTION_APPROVE',
  'SOCIAL_CONTRIBUTION_REJECT',
  'SOCIAL_DISBURSEMENT_REQUEST',
  'SOCIAL_DISBURSEMENT_APPROVE',
  'SOCIAL_DISBURSEMENT_REJECT',
  'INVESTMENT_CREATE',
  'INVESTMENT_ACTIVATE',
  'INVESTMENT_CANCEL',
  'INVESTMENT_RETURN',
  'INVESTMENT_LOSS',
  'INCOME_EXPENSE_CREATE',
  'INCOME_EXPENSE_APPROVE',
  'INCOME_EXPENSE_REJECT',
  'PAYOUT_PREVIEW',
  'PAYOUT_CONFIRM',
  'PAYOUT_PAID',
  'PAYOUT_CANCEL',
  'BACKUP',
  'RESTORE_BACKUP',
  'WHATSAPP_SHARE',
  'SUBSCRIPTION_INIT',
  'SUBSCRIPTION_TRIAL_START',
  'SUBSCRIPTION_PAYMENT_INITIATED',
  'SUBSCRIPTION_PAYMENT_SUCCESS',
  'SUBSCRIPTION_PAYMENT_FAILED',
  'SUBSCRIPTION_ACTIVATED',
  'SUBSCRIPTION_RENEWED',
  'OTHER',
] as const

/** Entity type strings currently written by `AuditService.record(...)`. */
export const AUDIT_ENTITY_TYPES = [
  'Loan',
  'LoanRepayment',
  'LoanSettings',
  'LoanGuarantor',
  'Fine',
  'FineSettings',
  'FinePayment',
  'Cooperative',
  'CooperativeSubscription',
  'SubscriptionPayment',
  'User',
  'StoredFile',
  'IncomeExpenseTransaction',
  'Contribution',
  'ContributionPeriod',
  'ContributionImport',
  'SharePurchase',
  'SpecialContributionCampaign',
  'SpecialContribution',
  'SocialContribution',
  'SocialDisbursement',
  'SocialFundSettings',
  'Investment',
  'InvestmentReturn',
  'PayoutRun',
  'HistoricalImport',
  'Report',
] as const

const HIDDEN_FIELD_KEYS = new Set([
  'id',
  'userid',
  'memberuserid',
  'entityid',
  'cooperativeid',
  'loanid',
  'issuedby',
  'bootstrap',
  'version',
  'deleted',
  'passwordhash',
  'failedloginattempts',
  'useragent',
])

const HIDDEN_KEY_SUBSTRINGS = ['password', 'token', 'secret', 'hash'] as const

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const MONEY_KEY_RE =
  /(amount|price|principal|pool|capital|writtenoff|dailyincrement|fee|balance|total)$/i

const PERCENT_KEY_RE = /(percent|rate)$/i

const DATE_KEY_RE = /(date|at)$/i

export interface AuditChangeRow {
  key: string
  label: string
  previous: string
  next: string
}

export interface AuditChangesView {
  rows: AuditChangeRow[]
  empty: boolean
  hasPrevious: boolean
  hasNew: boolean
}

export function isHiddenAuditField(key: string): boolean {
  const segment = lastSegment(key).toLowerCase()
  if (!segment) return true
  if (HIDDEN_FIELD_KEYS.has(segment)) return true
  if (HIDDEN_KEY_SUBSTRINGS.some((part) => segment.includes(part))) return true
  if (segment !== 'nationalid' && segment.endsWith('id')) return true
  return false
}

export function displayAuditAction(
  action: string | null | undefined,
  t: AuditTranslate,
): string {
  if (!action?.trim()) return MISSING_AUDIT_VALUE
  return translateIfExists(t, `auditLogs.actions.${action.trim()}`) ?? humanizeEnumToken(action)
}

export function displayAuditEntityType(
  entityType: string | null | undefined,
  t: AuditTranslate,
): string {
  if (!entityType?.trim()) return MISSING_AUDIT_VALUE
  const trimmed = entityType.trim()
  return translateIfExists(t, `auditLogs.entityTypes.${trimmed}`) ?? humanizePascalCase(trimmed)
}

export function displayAuditFieldLabel(key: string, t: AuditTranslate): string {
  const field = lastSegment(key)
  if (!field) return MISSING_AUDIT_VALUE
  return translateIfExists(t, `auditLogs.fields.${field}`) ?? humanizeFieldKey(field)
}

export function formatAuditDateTime(value: string | null | undefined): string {
  if (!value?.trim()) return MISSING_AUDIT_VALUE
  const parsed = dayjs(value)
  if (!parsed.isValid()) return value.trim()
  if (/^\d{4}-\d{2}-\d{2}$/.test(value.trim())) {
    return parsed.format('D MMM YYYY')
  }
  return parsed.format('D MMM YYYY, HH:mm')
}

export function formatAuditValue(
  raw: unknown,
  key: string,
  t: AuditTranslate,
  entityType?: string | null,
): string {
  if (raw == null || raw === '') return MISSING_AUDIT_VALUE
  if (typeof raw === 'boolean') {
    return raw ? t('common.yes') : t('common.no')
  }
  if (Array.isArray(raw)) {
    if (raw.length === 0) return MISSING_AUDIT_VALUE
    return raw
      .map((item) => formatAuditValue(item, key, t, entityType))
      .filter((item) => item !== MISSING_AUDIT_VALUE)
      .join(', ')
  }
  if (typeof raw === 'object') {
    return Object.entries(raw as Record<string, unknown>)
      .filter(([childKey]) => !isHiddenAuditField(childKey))
      .map(
        ([childKey, childValue]) =>
          `${displayAuditFieldLabel(childKey, t)}: ${formatAuditValue(childValue, childKey, t, entityType)}`,
      )
      .join(', ')
  }

  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return formatScalar(String(raw), key, t, entityType, raw)
  }
  if (typeof raw === 'string') {
    const trimmed = raw.trim()
    if (!trimmed) return MISSING_AUDIT_VALUE
    if (isUuid(trimmed)) return MISSING_AUDIT_VALUE
    return formatScalar(trimmed, key, t, entityType, trimmed)
  }
  return String(raw)
}

export function buildAuditChangeRows(
  previousValues: string | null | undefined,
  newValues: string | null | undefined,
  t: AuditTranslate,
  entityType?: string | null,
): AuditChangesView {
  const previousParsed = parseAuditPayload(previousValues)
  const newParsed = parseAuditPayload(newValues)

  const previousMap = previousParsed.kind === 'map' ? previousParsed.map : {}
  const newMap = newParsed.kind === 'map' ? newParsed.map : {}
  const keys = uniqueKeys([...Object.keys(previousMap), ...Object.keys(newMap)]).filter(
    (key) => !isHiddenAuditField(key),
  )

  const rows: AuditChangeRow[] = keys
    .map((key) => {
      const previousRaw = Object.prototype.hasOwnProperty.call(previousMap, key)
        ? previousMap[key]
        : undefined
      const nextRaw = Object.prototype.hasOwnProperty.call(newMap, key) ? newMap[key] : undefined
      if (isUuidValue(previousRaw) && isUuidValue(nextRaw)) return null
      if (isUuidValue(previousRaw) && nextRaw === undefined) return null
      if (isUuidValue(nextRaw) && previousRaw === undefined) return null
      const previous = isUuidValue(previousRaw)
        ? MISSING_AUDIT_VALUE
        : previousRaw === undefined
          ? MISSING_AUDIT_VALUE
          : formatAuditValue(previousRaw, key, t, entityType)
      const next = isUuidValue(nextRaw)
        ? MISSING_AUDIT_VALUE
        : nextRaw === undefined
          ? MISSING_AUDIT_VALUE
          : formatAuditValue(nextRaw, key, t, entityType)
      if (previous === MISSING_AUDIT_VALUE && next === MISSING_AUDIT_VALUE) return null
      return {
        key,
        label: displayAuditFieldLabel(key, t),
        previous,
        next,
      }
    })
    .filter((row): row is AuditChangeRow => row != null)

  if (previousParsed.kind === 'invalid' || newParsed.kind === 'invalid') {
    rows.push({
      key: '__details',
      label: t('auditLogs.fields.details', { defaultValue: 'Details' }),
      previous: previousParsed.kind === 'invalid' ? previousParsed.text : MISSING_AUDIT_VALUE,
      next: newParsed.kind === 'invalid' ? newParsed.text : MISSING_AUDIT_VALUE,
    })
  }

  const hasPrevious =
    previousParsed.kind === 'invalid' || rows.some((row) => row.previous !== MISSING_AUDIT_VALUE)
  const hasNew = newParsed.kind === 'invalid' || rows.some((row) => row.next !== MISSING_AUDIT_VALUE)

  return {
    rows,
    empty: rows.length === 0,
    hasPrevious,
    hasNew,
  }
}

export function buildAuditRecordLabel(log: Pick<AuditLog, 'entityType' | 'entityLabel'>, t: AuditTranslate): string {
  const typeLabel = displayAuditEntityType(log.entityType, t)
  const entity = auditEntityLabel(log)
  if (entity === MISSING_AUDIT_VALUE) return typeLabel
  if (typeLabel === MISSING_AUDIT_VALUE) return entity
  return `${typeLabel} · ${entity}`
}

export function buildAuditDescription(
  log: Pick<AuditLog, 'action' | 'entityType' | 'entityLabel' | 'userName' | 'username' | 'userId'>,
  t: AuditTranslate,
): string {
  const actor = auditUserLabel(log)
  const action = displayAuditAction(log.action, t)
  const record = auditEntityLabel(log)
  if (record !== MISSING_AUDIT_VALUE) {
    return t('auditLogs.description.withRecord', { actor, action, record })
  }
  return t('auditLogs.description.withoutRecord', { actor, action })
}

export function humanizeEnumToken(value: string): string {
  const words = value
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
  return sentenceCase(words)
}

export function humanizePascalCase(value: string): string {
  const spaced = value
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .trim()
  return sentenceCase(spaced.toLowerCase().split(/\s+/).filter(Boolean))
}

export function humanizeFieldKey(key: string): string {
  const fromSnake = lastSegment(key).replace(/[_-]+/g, ' ')
  const spaced = fromSnake
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .trim()
  return sentenceCase(spaced.toLowerCase().split(/\s+/).filter(Boolean))
}

type ParsedPayload =
  | { kind: 'empty' }
  | { kind: 'map'; map: Record<string, unknown> }
  | { kind: 'invalid'; text: string }

function parseAuditPayload(raw: string | null | undefined): ParsedPayload {
  if (raw == null || raw === '') return { kind: 'empty' }
  if (typeof raw !== 'string') return { kind: 'invalid', text: sanitizeReadable(String(raw)) }
  const trimmed = raw.trim()
  if (!trimmed) return { kind: 'empty' }
    try {
    const parsed = JSON.parse(trimmed) as unknown
    if (parsed == null) return { kind: 'empty' }
    if (Array.isArray(parsed) || typeof parsed !== 'object') {
      return { kind: 'invalid', text: sanitizeReadable(readableNonObject(parsed)) }
    }
    return { kind: 'map', map: flattenRecord(parsed as Record<string, unknown>) }
  } catch {
    return { kind: 'invalid', text: sanitizeReadable(trimmed) }
  }
}

function flattenRecord(input: Record<string, unknown>, prefix = ''): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      Object.assign(out, flattenRecord(value as Record<string, unknown>, path))
    } else {
      out[path] = value
    }
  }
  return out
}

function formatScalar(
  text: string,
  key: string,
  t: AuditTranslate,
  entityType: string | null | undefined,
  numericSource: string | number,
): string {
  const field = lastSegment(key)
  if (text === 'true' || text === 'false') {
    return text === 'true' ? t('common.yes') : t('common.no')
  }
  if (isMoneyKey(field)) {
    const money = tryFormatMoney(numericSource)
    if (money) return money
  }
  if (PERCENT_KEY_RE.test(field) && isNumericString(text)) {
    return `${trimNumeric(text)}%`
  }
  if (isDateKey(field) || looksLikeDate(text)) {
    const formatted = formatAuditDateTime(text)
    if (formatted !== MISSING_AUDIT_VALUE) return formatted
  }
  if (isEnumToken(text)) {
    return formatEnumValue(text, field, t, entityType)
  }
  return text
}

function formatEnumValue(
  value: string,
  field: string,
  t: AuditTranslate,
  entityType?: string | null,
): string {
  const candidates = enumTranslationKeys(value, field, entityType)
  for (const key of candidates) {
    const translated = translateIfExists(t, key)
    if (translated) return translated
  }
  return humanizeEnumToken(value)
}

function enumTranslationKeys(value: string, field: string, entityType?: string | null): string[] {
  const keys: string[] = []
  if (field === 'guaranteeMode' || value === 'SELF' || value === 'GUARANTOR') {
    if (value === 'SELF') keys.push('loans.request.guaranteeModeSelf')
    if (value === 'GUARANTOR') keys.push('loans.request.guaranteeModeGuarantor')
  }
  if (field === 'repaymentDateModel') {
    keys.push(`loans.schedule.repaymentModel.${value}`)
  }
  if (field === 'fineMode' || field === 'calculationMode') {
    keys.push(`fines.calculationMode.${value}`)
  }
  if (field === 'type' || field === 'fineType') {
    keys.push(`fines.fineType.${value}`)
  }
  if (field === 'category') {
    keys.push(`transactions.category.${value}`)
  }
  if (field === 'role' || field === 'roleInCooperative') {
    keys.push(`members.roles.${value}`)
  }
  if (field === 'ledgerEffect') {
    keys.push(`transactions.ledgerEffect.${value}`)
  }
  if (field === 'reviewStatus') {
    keys.push(`contributions.reviewStatus.${value}`)
  }
  if (field === 'accountStatus' || field === 'membershipStatus') {
    keys.push(`status.${value}`)
  }
  keys.push(...statusKeysForEntity(entityType, value))
  keys.push(`status.${value}`)
  return keys
}

function statusKeysForEntity(entityType: string | null | undefined, value: string): string[] {
  switch (entityType) {
    case 'Loan':
    case 'LoanRepayment':
    case 'LoanGuarantor':
    case 'LoanSettings':
      return [`loans.status.${value}`]
    case 'Fine':
    case 'FineSettings':
      return [`fines.status.${value}`]
    case 'FinePayment':
      return [`fines.paymentStatus.${value}`, `fines.status.${value}`]
    case 'SharePurchase':
      return [`shares.status.${value}`]
    case 'Contribution':
    case 'ContributionPeriod':
      return [`contributions.status.${value}`, `contributions.reviewStatus.${value}`]
    case 'Investment':
    case 'InvestmentReturn':
      return [`investments.status.${value}`]
    case 'PayoutRun':
      return [`payouts.status.${value}`]
    case 'IncomeExpenseTransaction':
      return [`transactions.status.${value}`]
    case 'SocialContribution':
    case 'SocialDisbursement':
    case 'SocialFundSettings':
      return [`socialFund.status.${value}`]
    case 'Cooperative':
    case 'User':
      return [`status.${value}`]
    default:
      return [
        `loans.status.${value}`,
        `fines.status.${value}`,
        `fines.paymentStatus.${value}`,
        `shares.status.${value}`,
        `contributions.status.${value}`,
        `investments.status.${value}`,
        `payouts.status.${value}`,
        `transactions.status.${value}`,
        `socialFund.status.${value}`,
      ]
  }
}

function tryFormatMoney(value: string | number): string | null {
  try {
    return formatMoney(value)
  } catch {
    return null
  }
}

function isMoneyKey(key: string): boolean {
  if (PERCENT_KEY_RE.test(key) && !/amount/i.test(key)) return false
  return MONEY_KEY_RE.test(key)
}

function isDateKey(key: string): boolean {
  return DATE_KEY_RE.test(key)
}

function looksLikeDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/.test(value)
}

function isNumericString(value: string): boolean {
  return /^-?\d+(\.\d+)?$/.test(value)
}

function trimNumeric(value: string): string {
  if (!value.includes('.')) return value
  return value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
}

function isEnumToken(value: string): boolean {
  return /^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$/.test(value) || /^[A-Z]{2,}$/.test(value)
}

function isUuid(value: string): boolean {
  return UUID_RE.test(value.trim())
}

function isUuidValue(value: unknown): boolean {
  return typeof value === 'string' && isUuid(value)
}

function lastSegment(key: string): string {
  const parts = key.split('.')
  return parts[parts.length - 1] ?? key
}

function uniqueKeys(keys: string[]): string[] {
  return [...new Set(keys)]
}

function sentenceCase(words: string[]): string {
  if (words.length === 0) return ''
  const [first, ...rest] = words
  const head = (first ?? '').charAt(0).toUpperCase() + (first ?? '').slice(1)
  return rest.length > 0 ? `${head} ${rest.join(' ')}` : head
}

function translateIfExists(t: AuditTranslate, key: string): string | null {
  const value = t(key, { defaultValue: '' })
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed || trimmed === key) return null
  return trimmed
}

function sanitizeReadable(value: string): string {
  const cleaned = value.replace(/[\u0000-\u001F\u007F]/g, ' ').replace(/\s+/g, ' ').trim()
  if (!cleaned) return MISSING_AUDIT_VALUE
  if (cleaned.length > 240) return `${cleaned.slice(0, 237)}…`
  return cleaned
}

function readableNonObject(value: unknown): string {
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}
