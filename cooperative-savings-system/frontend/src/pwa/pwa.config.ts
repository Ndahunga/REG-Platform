/**
 * PWA configuration notes (Phase 12 — vite-plugin-pwa).
 *
 * Enabled:
 * - Web app manifest (name, icons, theme-color #1B4D8C, display standalone)
 * - Service worker caching for app shell / static assets only
 * - Install prompt UI (deferred install button)
 * - Offline fallback page explaining that financial ops require connectivity
 * - Update prompt via registerType: 'prompt'
 *
 * Explicitly NOT planned for offline:
 * - Contributions, loans, payouts, or any money movement
 * - Caching /api/** responses for mutation
 * - Optimistic ledger writes without server confirmation
 */
export const pwaConfig = {
  enabled: import.meta.env.VITE_ENABLE_PWA === 'true',
  phase: 12,
  cacheAppShellOnly: true,
  allowOfflineFinancialOps: false,
  themeColor: '#1B4D8C',
  backgroundColor: '#FFFFFF',
} as const
