import dayjs, { type Dayjs } from 'dayjs'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

export type DateRangeIssue = 'invalid' | 'fromAfterTo' | 'futureFrom' | 'futureTo'

export type YearMonthIssue = 'invalidYear' | 'invalidMonth' | 'futureYearMonth'

export function todayIsoDate(today: Dayjs = dayjs()): string {
  return today.format('YYYY-MM-DD')
}

export function isIsoDate(value: string): boolean {
  if (!ISO_DATE.test(value)) return false
  const parsed = dayjs(value)
  return parsed.isValid() && parsed.format('YYYY-MM-DD') === value
}

export function validateOptionalDateRange(
  from: string,
  to: string,
  today: Dayjs = dayjs(),
): DateRangeIssue | null {
  if (from && !isIsoDate(from)) return 'invalid'
  if (to && !isIsoDate(to)) return 'invalid'
  const todayDate = today.startOf('day')
  if (from && dayjs(from).isAfter(todayDate, 'day')) return 'futureFrom'
  if (to && dayjs(to).isAfter(todayDate, 'day')) return 'futureTo'
  if (from && to && dayjs(to).isBefore(dayjs(from), 'day')) return 'fromAfterTo'
  return null
}

export function validateOptionalYearMonth(
  year: string,
  month: string,
  today: Dayjs = dayjs(),
): YearMonthIssue | null {
  if (year) {
    const y = Number(year)
    if (!Number.isInteger(y) || y < 2000 || y > 2100) return 'invalidYear'
  }
  if (month) {
    const m = Number(month)
    if (!Number.isInteger(m) || m < 1 || m > 12) return 'invalidMonth'
  }
  if (year && month) {
    const selected = dayjs(`${year}-${String(month).padStart(2, '0')}-01`)
    if (selected.isValid() && selected.isAfter(today, 'month')) return 'futureYearMonth'
  } else if (year && Number(year) > today.year()) {
    return 'futureYearMonth'
  }
  return null
}

export function filterValidationMessageKey(
  issue: DateRangeIssue | YearMonthIssue | null,
): string | undefined {
  return issue ? `common.filterValidation.${issue}` : undefined
}
