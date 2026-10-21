/**
 * Preview login is ONLY for local UI exploration.
 * It requires BOTH Vite DEV mode and an explicit opt-in flag.
 * Production / staging builds never enable this path.
 */
export function isPreviewLoginEnabled(
  env: Pick<ImportMetaEnv, 'DEV' | 'PROD' | 'MODE' | 'VITE_ENABLE_PREVIEW_LOGIN' | 'VITE_APP_ENV'> & {
    DEV?: boolean
    PROD?: boolean
    MODE?: string
  } = import.meta.env,
): boolean {
  if (env.PROD === true) return false
  if (env.DEV !== true) return false
  const appEnv = (env.VITE_APP_ENV || '').toLowerCase()
  if (appEnv === 'production' || appEnv === 'staging' || appEnv === 'prod') return false
  return String(env.VITE_ENABLE_PREVIEW_LOGIN || '').toLowerCase() === 'true'
}
