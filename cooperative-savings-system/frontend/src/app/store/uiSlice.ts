import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

export type ThemePreference = 'light' | 'dark'
export type ThemeMode = 'light' | 'dark'

const THEME_STORAGE_KEY = 'csams.theme'
export const SIDEBAR_PINNED_STORAGE_KEY = 'csams.sidebarPinned'

function readStoredTheme(): ThemePreference {
  try {
    const raw = localStorage.getItem(THEME_STORAGE_KEY)
    if (raw === 'dark') return 'dark'
    if (raw === 'light') return 'light'
    // Legacy "system" followed the OS and usually looked like light.
  } catch {
    // ignore storage failures
  }
  return 'light'
}

/** Desktop sidebar pin preference. Anything other than an explicit "false" means pinned (the default). */
export function readStoredSidebarPinned(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY) !== 'false'
  } catch {
    return true
  }
}

export function resolveThemeMode(preference: ThemePreference): ThemeMode {
  return preference === 'dark' ? 'dark' : 'light'
}

export interface UiState {
  sidebarOpen: boolean
  themePreference: ThemePreference
  /**
   * Desktop (md+) sidebar: pinned = permanently visible beside the content; unpinned = hidden and opened
   * on demand as an overlay. Optional so existing state shapes keep working; undefined means pinned.
   */
  sidebarPinned?: boolean
}

const initialState: UiState = {
  sidebarOpen: false,
  themePreference: readStoredTheme(),
  sidebarPinned: readStoredSidebarPinned(),
}

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    setSidebarOpen(state, action: PayloadAction<boolean>) {
      state.sidebarOpen = action.payload
    },
    toggleSidebar(state) {
      state.sidebarOpen = !state.sidebarOpen
    },
    setSidebarPinned(state, action: PayloadAction<boolean>) {
      state.sidebarPinned = action.payload
      try {
        localStorage.setItem(SIDEBAR_PINNED_STORAGE_KEY, String(action.payload))
      } catch {
        // ignore storage failures; the preference still applies for this session
      }
    },
    setThemePreference(state, action: PayloadAction<ThemePreference>) {
      state.themePreference = action.payload
      try {
        localStorage.setItem(THEME_STORAGE_KEY, action.payload)
      } catch {
        // ignore
      }
    },
    /** @deprecated Prefer setThemePreference */
    setThemeMode(state, action: PayloadAction<ThemeMode>) {
      state.themePreference = action.payload
      try {
        localStorage.setItem(THEME_STORAGE_KEY, action.payload)
      } catch {
        // ignore
      }
    },
  },
})

export const { setSidebarOpen, toggleSidebar, setSidebarPinned, setThemePreference, setThemeMode } =
  uiSlice.actions

export const selectSidebarPinned = (state: { ui: UiState }) => state.ui.sidebarPinned !== false
export default uiSlice.reducer
