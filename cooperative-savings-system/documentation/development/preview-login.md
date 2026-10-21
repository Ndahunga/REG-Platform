# Preview / DEV authentication (frontend)
#
# LoginPage may show a "Dev preview" button only when ALL are true:
# - Vite is running in DEV mode (`npm run dev`)
# - `VITE_ENABLE_PREVIEW_LOGIN=true`
# - `VITE_APP_ENV` is not production/staging
#
# Production builds (`vite build`) set `import.meta.env.PROD=true`, so preview
# login is impossible even if the env flag is mistakenly set.
# Prefer real backend login for local testing whenever PostgreSQL is available.
#
# See: frontend/src/shared/auth/previewLogin.ts
