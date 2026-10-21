# Phase 12 completion — PWA installability

**Date:** 2026-08-04  
**Status:** Complete (frontend)

## Delivered

### Service worker & manifest

- `vite-plugin-pwa` gated by `VITE_ENABLE_PWA` (`selfDestroying` when false)
- Manifest: TERIMBERE CSAMS / TERIMBERE, `display: standalone`, theme `#0F5C5C`, sand background `#F5F1EA`
- Icons: `public/icons/icon-192.png`, `icon-512.png`, `apple-touch-icon.png`, `icon.svg`
- Manual registration via `src/pwa/registerPwa.ts` (`injectRegister: false`, `registerType: 'prompt'`)
- Workbox caches app shell / static assets only — never caches `/api` responses
- `public/offline.html` offline messaging page

### Install / update UX

- `PwaInstallButton` + `useInstallPrompt` (`beforeinstallprompt`)
- `PwaUpdateBanner` + `usePwaUpdate` (reload to apply waiting SW)

### Offline financial safety

- `OfflineBanner` in `AppLayout`
- `useOnlineStatus`, `useServerReachable`, `useFinancialSubmitGuard`
- `FinancialActionButton` on contribution period save
- Axios request interceptor rejects `POST`/`PUT`/`PATCH`/`DELETE` when offline

### Push prep

- `subscribeToPush()` stub documents intended VAPID flow; throws “Push backend not configured (Phase 12 prep)”

### i18n / env / docs

- en/rw keys under `pwa.*`
- `VITE_ENABLE_PWA=true` in `.env.local`, `.env.staging`, `.env.production`; documented in `.env.example`
- `frontend/src/pwa/README.md`

## Verification

```bash
cd cooperative-savings-system/frontend
npm test -- --run
# with VITE_ENABLE_PWA=true (default in .env.production / .env.local):
npm run build
```

## Policy

Financial operations always require connectivity. Cached shell viewing is allowed; money movement is never applied offline.
