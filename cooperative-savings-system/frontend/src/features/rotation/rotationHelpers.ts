import dayjs from 'dayjs'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import type { Member } from '@/shared/types/member'

/** 7000 -> "70", 3050 -> "30.5", 3333 -> "33.33". */
export function bpsToPercent(bps: number): string {
  return String(Number((bps / 100).toFixed(2)))
}

/**
 * Parses a percentage typed by the user into basis points (at most two decimals). Returns null when it is not a number
 * between 0 and 100 or has more than two decimals.
 */
export function percentToBps(text: string): number | null {
  const trimmed = text.trim().replace(',', '.')
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(trimmed)) return null
  const bps = Math.round(Number(trimmed) * 100)
  return bps >= 0 && bps <= 10000 ? bps : null
}

export type SplitProblem = 'required' | 'invalid' | 'hybridBounds'

/**
 * The rotating portion the user may submit for a model. ROTATING is always 100%; HYBRID must keep both portions
 * positive. Returns the basis points or the reason it is not acceptable.
 */
export function resolveRotatingBps(
  model: SchemeOperatingModel,
  rotatingPercentText: string,
): { bps: number } | { problem: SplitProblem } {
  if (model === 'ROTATING') return { bps: 10000 }
  if (!rotatingPercentText.trim()) return { problem: 'required' }
  const bps = percentToBps(rotatingPercentText)
  if (bps === null) return { problem: 'invalid' }
  if (bps <= 0 || bps >= 10000) return { problem: 'hybridBounds' }
  return { bps }
}

export function monthKey(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, '0')}`
}

/** `2026-03` from a `<input type="month">` value; null when malformed. */
export function parseMonthInput(value: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  if (month < 1 || month > 12) return null
  return { year, month }
}

/** First month that has not begun yet, as `YYYY-MM`. Configurations and cycles can only start from here on. */
export function firstSelectableMonth(now: Date = new Date()): string {
  return dayjs(now).add(1, 'month').format('YYYY-MM')
}

/** The months a cycle of `count` recipients starting at `start` would occupy, as `YYYY-MM` strings. */
export function cycleMonths(start: string, count: number): string[] {
  const parsed = parseMonthInput(start)
  if (!parsed) return []
  const first = dayjs(`${monthKey(parsed.year, parsed.month)}-01`)
  return Array.from({ length: count }, (_, i) => first.add(i, 'month').format('YYYY-MM'))
}

/** A random key for one create request, reused when the same dialog submits again (a double click or retry). */
export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto as { randomUUID?: () => string } | undefined
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID()
  return `rot-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/** A member's display name, falling back to the username. */
export function memberLabel(member: Pick<Member, 'firstName' | 'lastName' | 'username'>): string {
  const name = `${member.firstName ?? ''} ${member.lastName ?? ''}`.trim()
  return name || member.username
}
