import type { AuditLog } from '@/shared/types/auditLog'

export function parseJsonSafe(value?: string | null): unknown {
  if (value == null || value === '') return null
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

export function toIsoDateStart(date: string): string | undefined {
  if (!date) return undefined
  return `${date}T00:00:00Z`
}

export function toIsoDateEnd(date: string): string | undefined {
  if (!date) return undefined
  return `${date}T23:59:59Z`
}

export function auditUserLabel(
  log: Pick<AuditLog, 'userName' | 'username' | 'userId'>,
): string {
  return log.userName?.trim() || log.username?.trim() || '—'
}

export function auditEntityLabel(
  log: Pick<AuditLog, 'entityLabel' | 'entityType'>,
): string {
  return log.entityLabel?.trim() || '—'
}
