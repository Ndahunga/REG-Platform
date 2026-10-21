import type { MomChangeState } from '@/shared/types/dashboard'

export interface MomDisplay {
  state: MomChangeState
  /** Safe numeric percent or null when no baseline. Never NaN/Infinity. */
  percent: number | null
  arrow: 'up' | 'down' | 'flat' | 'none'
}

/** Normalize MoM fields from the insights API for UI rendering. */
export function resolveMomDisplay(
  changePercent: string | number | null | undefined,
  changeState: MomChangeState | undefined,
): MomDisplay {
  const state = changeState ?? 'FLAT'

  if (state === 'NO_BASELINE') {
    return { state: 'NO_BASELINE', percent: null, arrow: 'none' }
  }

  if (changePercent === null || changePercent === undefined) {
    return { state: 'FLAT', percent: null, arrow: 'none' }
  }

  const raw = Number(changePercent)
  const percent = Number.isFinite(raw) ? raw : null

  if (percent === null) {
    return { state: 'FLAT', percent: null, arrow: 'none' }
  }

  if (state === 'UP' || percent > 0) {
    return { state: 'UP', percent, arrow: 'up' }
  }

  if (state === 'DOWN' || percent < 0) {
    return { state: 'DOWN', percent, arrow: 'down' }
  }

  return { state: 'FLAT', percent: 0, arrow: 'flat' }
}

export function formatMomPercent(percent: number | null): string {
  if (percent === null || !Number.isFinite(percent)) return ''
  const abs = Math.abs(percent)
  return `${abs.toFixed(1)}%`
}
