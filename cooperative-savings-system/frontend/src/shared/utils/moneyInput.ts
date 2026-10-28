/**
 * Thousand separators while a money amount is typed: "530000" shows as "530,000". The separators are only for the
 * eye: forms, validation and the API always receive the plain number ("530000").
 */

/** "530,000.5" -> "530000.5"; keeps only digits and the first decimal point. */
export function plainMoney(text: string | number | null | undefined): string {
  if (text === null || text === undefined) return ''
  const s = String(text).replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  return dot < 0 ? s : s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '')
}

/** "530000.5" -> "530,000.5" (the decimals are left as typed). */
export function groupedMoney(text: string | number | null | undefined): string {
  const plain = plainMoney(text)
  if (!plain) return ''
  const [whole, fraction] = plain.split('.')
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? grouped : `${grouped}.${fraction}`
}

/** Where the caret goes in the grouped text so it stays after the same digit it was after while typing. */
export function caretAfterGrouping(typed: string, caret: number, grouped: string): number {
  const significant = plainMoney(typed.slice(0, caret)).length
  if (significant === 0) return 0
  let seen = 0
  for (let i = 0; i < grouped.length; i++) {
    if (grouped[i] !== ',') seen++
    if (seen === significant) return i + 1
  }
  return grouped.length
}

/** A stored amount shown in a field: "2500.0000" -> "2,500", "2500.5000" -> "2,500.5". */
export function displayMoney(text: string | number | null | undefined): string {
  const plain = plainMoney(text)
  if (!plain.includes('.')) return groupedMoney(plain)
  const trimmed = plain.replace(/0+$/, '').replace(/\.$/, '')
  return groupedMoney(trimmed)
}
