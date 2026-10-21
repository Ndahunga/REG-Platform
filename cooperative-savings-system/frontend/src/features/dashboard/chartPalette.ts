/** Shared dashboard chart colors (brand palette already used by the existing dashboard charts). */
export const CHART_COLORS = {
  blue: '#1B4D8C',
  orange: '#FF7A00',
  green: '#16A34A',
  purple: '#7C3AED',
  red: '#C62828',
  neutral: '#94A3B8',
} as const

const compactFormatter = new Intl.NumberFormat('en', {
  notation: 'compact',
  maximumFractionDigits: 1,
})

/** Short axis/label number such as 450K. Never returns NaN or Infinity text. */
export function compactNumber(value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) ? compactFormatter.format(n) : '0'
}

export function truncateLabel(label: string, max = 18): string {
  return label.length > max ? `${label.slice(0, Math.max(1, max - 1))}…` : label
}
