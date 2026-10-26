import type { RotationTurnMode, ShareCycleTurnInput } from '@/shared/types/rotation'

export interface ShareHolder {
  userId: string
  label: string
  shares: number
}

/** A turn being edited: month index starts at 1 (the start month). */
export interface DraftTurn extends ShareCycleTurnInput {
  key: string
}

let counter = 0
export const draftKey = () => `t${++counter}`

/**
 * Places every share of every member in the months of the cycle.
 *
 * - ONE_SHARE_PER_TURN: each share is its own turn; members' first shares come before anyone's second share, so
 *   members with several shares are spread over the cycle.
 * - ALL_SHARES_TOGETHER: each member has one turn with all their shares.
 * - GROUPED_BY_MONTH: as ONE_SHARE_PER_TURN, but a member's shares that land in the same month become one turn.
 *
 * Months are filled in order, as evenly as possible (e.g. 16 shares over 12 months: 4 months hold two turns).
 */
export function autoArrange(holders: ShareHolder[], months: number, mode: RotationTurnMode): DraftTurn[] {
  const units: { userId: string; shares: number }[] = []
  const active = holders.filter((h) => h.shares > 0)
  if (mode === 'ALL_SHARES_TOGETHER') {
    active.forEach((h) => units.push({ userId: h.userId, shares: h.shares }))
  } else {
    const maxShares = Math.max(0, ...active.map((h) => h.shares))
    for (let round = 0; round < maxShares; round++) {
      active.forEach((h) => {
        if (h.shares > round) units.push({ userId: h.userId, shares: 1 })
      })
    }
  }
  if (units.length === 0 || months <= 0) return []
  const turns: DraftTurn[] = []
  const base = Math.floor(units.length / months)
  const extra = units.length % months
  let index = 0
  for (let m = 1; m <= months && index < units.length; m++) {
    const count = Math.max(1, base + (m <= extra ? 1 : 0))
    for (let k = 0; k < count && index < units.length; k++) {
      const unit = units[index++]
      const existing = turns.find((t) => t.monthIndex === m && t.memberUserId === unit.userId)
      if (existing) existing.shares += unit.shares
      else turns.push({ key: draftKey(), monthIndex: m, memberUserId: unit.userId, shares: unit.shares })
    }
  }
  return turns
}

export type TurnProblem =
  | { code: 'MONTH_EMPTY'; month: number }
  | { code: 'MEMBER_TWICE_IN_MONTH'; month: number }
  | { code: 'TOO_MANY_SHARES'; userId: string; held: number; placed: number }
  | { code: 'NOT_ONE_SHARE'; month: number }
  | { code: 'MEMBER_SEVERAL_TURNS'; userId: string }

export interface TurnCheck {
  problems: TurnProblem[]
  /** Members whose shares are not all placed (a warning, not an error). */
  unplaced: { userId: string; missing: number }[]
  sharesPlaced: number
}

export function checkTurns(turns: ShareCycleTurnInput[], holders: ShareHolder[], months: number, mode: RotationTurnMode): TurnCheck {
  const problems: TurnProblem[] = []
  for (let m = 1; m <= months; m++) {
    const inMonth = turns.filter((t) => t.monthIndex === m)
    if (inMonth.length === 0) problems.push({ code: 'MONTH_EMPTY', month: m })
    if (new Set(inMonth.map((t) => t.memberUserId)).size !== inMonth.length) {
      problems.push({ code: 'MEMBER_TWICE_IN_MONTH', month: m })
    }
    if (mode === 'ONE_SHARE_PER_TURN' && inMonth.some((t) => t.shares !== 1)) problems.push({ code: 'NOT_ONE_SHARE', month: m })
  }
  const placed = new Map<string, number>()
  const turnCount = new Map<string, number>()
  turns.forEach((t) => {
    placed.set(t.memberUserId, (placed.get(t.memberUserId) ?? 0) + t.shares)
    turnCount.set(t.memberUserId, (turnCount.get(t.memberUserId) ?? 0) + 1)
  })
  const unplaced: { userId: string; missing: number }[] = []
  holders.forEach((h) => {
    const p = placed.get(h.userId) ?? 0
    if (p > h.shares) problems.push({ code: 'TOO_MANY_SHARES', userId: h.userId, held: h.shares, placed: p })
    else if (p < h.shares) unplaced.push({ userId: h.userId, missing: h.shares - p })
    if (mode === 'ALL_SHARES_TOGETHER' && (turnCount.get(h.userId) ?? 0) > 1) {
      problems.push({ code: 'MEMBER_SEVERAL_TURNS', userId: h.userId })
    }
  })
  return { problems, unplaced, sharesPlaced: [...placed.values()].reduce((a, b) => a + b, 0) }
}

export function monthOf(start: string, index: number): string {
  const [y, m] = start.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + index - 1, 1))
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}

export const money = (v: number | string | null | undefined) => Math.round(Number(v ?? 0)).toLocaleString()
