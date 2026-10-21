export const EMPTY_MONEY_PLACEHOLDER = '—'

function isEmptyMoneyValue(value: unknown): boolean {
  return value == null || (typeof value === 'string' && value.trim() === '')
}

/**
 * Format money from integer minor units or a decimal string without binary float math.
 * Prefer passing amounts as decimal strings (e.g. "1250.50") from the API.
 * Null, undefined, or blank optional values render as "—" and do not throw.
 */
export function normalizeDecimalString(value: string | number): string {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      throw new Error('Invalid monetary amount')
    }
    // Avoid toString scientific notation for large/small values; use fixed then trim.
    const fixed = value.toFixed(8)
    return trimTrailingZeros(fixed)
  }

  const trimmed = value.trim()
  if (!/^-?\d+(\.\d+)?$/.test(trimmed)) {
    throw new Error(`Invalid monetary amount: ${value}`)
  }
  return trimTrailingZeros(trimmed)
}

function trimTrailingZeros(decimal: string): string {
  if (!decimal.includes('.')) return decimal
  return decimal.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
}

function splitParts(decimal: string): { sign: string; whole: string; fraction: string } {
  const sign = decimal.startsWith('-') ? '-' : ''
  const raw = sign ? decimal.slice(1) : decimal
  const [whole = '0', fraction = ''] = raw.split('.')
  return { sign, whole, fraction }
}

export type MoneyAmount = string | number | null | undefined

export function formatMoney(
  amount: MoneyAmount,
  options: {
    currency?: string
    locale?: string
    minimumFractionDigits?: number
    maximumFractionDigits?: number
  } = {},
): string {
  if (isEmptyMoneyValue(amount)) {
    return EMPTY_MONEY_PLACEHOLDER
  }

  const {
    currency = 'RWF',
    locale = 'en-RW',
    minimumFractionDigits = 0,
    maximumFractionDigits = 2,
  } = options

  const normalized = normalizeDecimalString(amount as string | number)
  const { sign, whole, fraction } = splitParts(normalized)

  const paddedFraction = fraction
    .padEnd(maximumFractionDigits, '0')
    .slice(0, maximumFractionDigits)
  const visibleFraction =
    maximumFractionDigits === 0
      ? ''
      : paddedFraction.padEnd(minimumFractionDigits, '0').replace(/0+$/, '').padEnd(
          Math.min(minimumFractionDigits, maximumFractionDigits),
          '0',
        )

  const groupedWhole = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const numberPart =
    visibleFraction.length > 0
      ? `${sign}${groupedWhole}.${visibleFraction}`
      : `${sign}${groupedWhole}`

  try {
    // Use Intl for currency symbol/placement only; numeric value comes from our string path.
    const sample = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).format(0)

    if (sample.includes('0')) {
      return sample.replace('0', numberPart)
    }
  } catch {
    // fall through
  }

  return `${currency} ${numberPart}`
}

/** Presentation helper: never throw. Invalid or empty optional amounts show "—". */
export function formatOptionalMoney(
  amount: MoneyAmount,
  options?: Parameters<typeof formatMoney>[1],
): string {
  if (isEmptyMoneyValue(amount)) {
    return EMPTY_MONEY_PLACEHOLDER
  }
  try {
    return formatMoney(amount, options)
  } catch {
    return EMPTY_MONEY_PLACEHOLDER
  }
}
