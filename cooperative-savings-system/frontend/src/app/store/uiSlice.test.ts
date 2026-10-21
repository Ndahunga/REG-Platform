import { afterEach, describe, expect, it } from 'vitest'
import uiReducer, {
  readStoredSidebarPinned,
  resolveThemeMode,
  SIDEBAR_PINNED_STORAGE_KEY,
  selectSidebarPinned,
  setSidebarPinned,
} from '@/app/store/uiSlice'

describe('resolveThemeMode', () => {
  it('returns light and dark preferences directly', () => {
    expect(resolveThemeMode('light')).toBe('light')
    expect(resolveThemeMode('dark')).toBe('dark')
  })
})

describe('sidebar pin preference', () => {
  afterEach(() => localStorage.removeItem(SIDEBAR_PINNED_STORAGE_KEY))

  it('defaults to pinned when nothing (or anything unexpected) is stored', () => {
    localStorage.removeItem(SIDEBAR_PINNED_STORAGE_KEY)
    expect(readStoredSidebarPinned()).toBe(true)
    localStorage.setItem(SIDEBAR_PINNED_STORAGE_KEY, 'garbage')
    expect(readStoredSidebarPinned()).toBe(true)
  })

  it('reads an explicit stored value', () => {
    localStorage.setItem(SIDEBAR_PINNED_STORAGE_KEY, 'false')
    expect(readStoredSidebarPinned()).toBe(false)
    localStorage.setItem(SIDEBAR_PINNED_STORAGE_KEY, 'true')
    expect(readStoredSidebarPinned()).toBe(true)
  })

  it('setSidebarPinned updates the state and persists it', () => {
    const base = { sidebarOpen: false, themePreference: 'light' as const }
    const unpinned = uiReducer(base, setSidebarPinned(false))
    expect(unpinned.sidebarPinned).toBe(false)
    expect(localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY)).toBe('false')

    const pinned = uiReducer(unpinned, setSidebarPinned(true))
    expect(pinned.sidebarPinned).toBe(true)
    expect(localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY)).toBe('true')
  })

  it('treats a state without the field as pinned (backward compatible)', () => {
    expect(selectSidebarPinned({ ui: { sidebarOpen: false, themePreference: 'light' } })).toBe(true)
    expect(selectSidebarPinned({ ui: { sidebarOpen: false, themePreference: 'light', sidebarPinned: false } })).toBe(false)
    expect(selectSidebarPinned({ ui: { sidebarOpen: false, themePreference: 'light', sidebarPinned: true } })).toBe(true)
  })
})
