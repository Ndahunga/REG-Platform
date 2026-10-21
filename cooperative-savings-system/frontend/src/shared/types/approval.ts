export type ApprovalAction = 'SUBMITTED' | 'APPROVED' | 'REJECTED'

export interface ApprovalEvent {
  id: string
  cooperativeId?: string
  entityType?: string
  entityId?: string
  actorUserId?: string | null
  actorName?: string | null
  actorRole?: string | null
  action: ApprovalAction | string
  previousStatus?: string | null
  newStatus?: string | null
  comment?: string | null
  createdAt?: string | null
}

export function mapApprovalEvent(raw: ApprovalEvent): ApprovalEvent {
  return {
    ...raw,
    id: String(raw.id),
    actorUserId: raw.actorUserId != null ? String(raw.actorUserId) : null,
  }
}

export function formatApprovalStamp(event: ApprovalEvent): string {
  const when = event.createdAt
    ? new Date(event.createdAt).toLocaleString()
    : '—'
  const role = event.actorRole ? ` — ${event.actorRole}` : ''
  return `${event.actorName || event.actorUserId || 'Unknown'}${role} — ${when}`
}
