export interface AuditLog {
  id: string
  userId?: string | null
  userName?: string | null
  username?: string | null
  cooperativeId?: string | null
  action: string
  entityType?: string | null
  entityId?: string | null
  entityLabel?: string | null
  previousValues?: string | null
  newValues?: string | null
  ipAddress?: string | null
  userAgent?: string | null
  createdAt: string
}

export interface AuditLogQuery {
  action?: string
  userId?: string
  entityType?: string
  from?: string
  to?: string
  page?: number
  size?: number
  sort?: string
}
