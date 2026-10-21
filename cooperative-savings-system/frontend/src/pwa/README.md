# PWA (Phase 12)

Installable Progressive Web App for OuWealth Community. The service worker caches the **app shell and static assets only**. Financial API mutations are never queued or applied offline.

## Enable

Set `VITE_ENABLE_PWA=true` (see `.env.local`, `.env.staging`, `.env.production`).

When false, `vite-plugin-pwa` runs in `selfDestroying` mode and `registerPwa()` unregisters any existing worker.

## What is cached

- Precached JS/CSS/HTML/icons/fonts (workbox `globPatterns`)
- Optional runtime cache for Google Fonts
- SPA `navigateFallback` → `index.html` (API paths denylisted)
- `public/offline.html` — dedicated offline messaging page

## What is NOT cached

- `/api/**` responses
- Offline contributions, loans, payouts, or any money movement
- Optimistic ledger writes without server confirmation

## Modules

| File | Role |
|------|------|
| `registerPwa.ts` | Manual SW registration (`injectRegister: false`), update hooks |
| `PwaUpdateBanner.tsx` | “Update available” snackbar + reload |
| `PwaInstallButton.tsx` / `useInstallPrompt.ts` | `beforeinstallprompt` install UX |
| `pushPrep.ts` | Push architecture stub (no backend) |
| `pwa.config.ts` | Feature flags / policy constants |

## Offline financial guard

1. `OfflineBanner` in `AppLayout`
2. `FinancialActionButton` / `useFinancialSubmitGuard` on high-traffic forms (e.g. contribution period save)
3. Axios interceptor in `shared/api/client.ts` rejects `POST`/`PUT`/`PATCH`/`DELETE` when `navigator.onLine` is false

## Push (prep only)

`subscribeToPush()` checks Notification permission and service worker readiness, then throws  
`Push backend not configured (Phase 12 prep)`.

## Icons

Source: `public/icons/icon.svg`  
Rasterize: `node scripts/generate-pwa-icons.mjs` (requires `sharp`)
