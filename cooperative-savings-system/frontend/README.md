# TERIMBERE CSAMS — Frontend

Vite + React + TypeScript SPA for the Cooperative Savings Account Management System.

## Local development

```bash
cd cooperative-savings-system/frontend
npm install
npm run dev
```

App: [http://localhost:5173](http://localhost:5173)  
API proxy: `/api` → `http://localhost:8080`

Copy `.env.example` to `.env.local` if needed (a local file is already provided).

### Scripts

| Script | Description |
|--------|-------------|
| `npm run dev` | Vite dev server (port 5173) |
| `npm run build` | Typecheck + production build |
| `npm run preview` | Preview production build |
| `npm test` | Run Vitest once |
| `npm run test:watch` | Vitest watch mode |
| `npm run lint` | Oxlint |

### Notes

- Access tokens are kept in Redux memory only (not `localStorage`).
- PWA installability (Phase 12): set `VITE_ENABLE_PWA=true` to register the service worker. The SW caches the app shell only — financial mutations are blocked offline. See `src/pwa/README.md`.

### Docker

```bash
docker build -t terimbere-frontend .
docker run --rm -p 8088:80 terimbere-frontend
```
