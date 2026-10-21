import AssignmentTurnedInIcon from '@mui/icons-material/AssignmentTurnedIn'
import NotificationsIcon from '@mui/icons-material/Notifications'
import {
  Badge,
  Box,
  Button,
  Divider,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Menu,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import {
  NOTIFICATION_POLL_MS,
  isUnread,
  notificationBadgeCount,
  notificationTargetPath,
  pendingApprovalItems,
  pendingApprovalLabelKey,
  unreadHighlightSx,
} from './notificationHelpers'
import {
  fetchNotifications,
  fetchPendingApprovals,
  fetchUnreadCount,
  markNotificationRead,
} from '@/shared/api/notifications'
import { ROUTES } from '@/shared/constants/routes'
import type { AppNotification } from '@/shared/types/notification'

export function NotificationBell() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const accessToken = useAppSelector((s) => s.auth.accessToken)
  const [anchor, setAnchor] = useState<null | HTMLElement>(null)
  const open = Boolean(anchor)

  const unreadQuery = useQuery({
    queryKey: ['notifications-unread-count'],
    queryFn: fetchUnreadCount,
    enabled: Boolean(accessToken),
    refetchInterval: NOTIFICATION_POLL_MS,
    refetchOnWindowFocus: true,
    retry: 1,
  })

  const pendingQuery = useQuery({
    queryKey: ['notifications-pending-approvals'],
    queryFn: fetchPendingApprovals,
    enabled: Boolean(accessToken),
    refetchInterval: NOTIFICATION_POLL_MS,
    refetchOnWindowFocus: true,
    retry: 1,
  })

  const recentQuery = useQuery({
    queryKey: ['notifications', false, 0, 8],
    queryFn: () =>
      fetchNotifications({
        page: 0,
        size: 8,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(accessToken) && open,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] })
    void queryClient.invalidateQueries({ queryKey: ['notifications-unread-count'] })
    void queryClient.invalidateQueries({ queryKey: ['notifications-pending-approvals'] })
  }

  const markOne = useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: invalidate,
  })

  const badge = notificationBadgeCount(unreadQuery.data ?? 0, pendingQuery.data)
  const pendingItems = pendingApprovalItems(pendingQuery.data)
  const recent = recentQuery.data?.content ?? []

  const close = () => setAnchor(null)

  const go = (path: string) => {
    close()
    navigate(path)
  }

  const openNotification = (row: AppNotification) => {
    const path = notificationTargetPath(row)
    close()
    if (isUnread(row)) {
      markOne.mutate(row.id, {
        onSettled: () => {
          if (path) navigate(path)
        },
      })
      return
    }
    if (path) navigate(path)
  }

  return (
    <>
      <IconButton
        color="inherit"
        onClick={(e) => setAnchor(e.currentTarget)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t('notifications.open')}
        sx={{ minWidth: 44, minHeight: 44, color: '#FFFFFF' }}
      >
        <Badge color="error" badgeContent={badge > 99 ? '99+' : badge} invisible={badge <= 0}>
          <NotificationsIcon />
        </Badge>
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={open}
        onClose={close}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { width: 360, maxWidth: 'calc(100vw - 24px)', maxHeight: 480 } } }}
      >
        <Box sx={{ px: 2, py: 1.25 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
            {t('notifications.menuTitle')}
          </Typography>
        </Box>
        {pendingItems.length > 0 ? (
          <>
            <Typography variant="overline" sx={{ px: 2, color: 'text.secondary' }}>
              {t('notifications.pending.title')}
            </Typography>
            <List dense disablePadding>
              {pendingItems.map((item) => (
                <ListItemButton key={item.kind} onClick={() => go(item.path)} sx={{ py: 1.1 }}>
                  <ListItemIcon sx={{ minWidth: 36 }}>
                    <AssignmentTurnedInIcon fontSize="small" color="primary" />
                  </ListItemIcon>
                  <ListItemText
                    primary={t(pendingApprovalLabelKey(item.kind, item.count), {
                      count: item.count,
                    })}
                  />
                </ListItemButton>
              ))}
            </List>
            <Divider sx={{ my: 0.5 }} />
          </>
        ) : null}
        <Typography variant="overline" sx={{ px: 2, color: 'text.secondary' }}>
          {t('notifications.recent')}
        </Typography>
        {recent.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ px: 2, py: 1.5 }}>
            {t('notifications.emptyTitle')}
          </Typography>
        ) : (
          <List dense disablePadding>
            {recent.map((row) => (
              <ListItemButton
                key={row.id}
                onClick={() => openNotification(row)}
                sx={{
                  alignItems: 'flex-start',
                  py: 1.1,
                  ...unreadHighlightSx(isUnread(row)),
                }}
              >
                <ListItemText
                  primary={
                    <Typography
                      variant="body2"
                      sx={{ fontWeight: isUnread(row) ? 700 : 500 }}
                      noWrap
                    >
                      {row.title}
                    </Typography>
                  }
                  secondary={
                    row.body ? (
                      <Typography variant="caption" color="text.secondary" noWrap component="span">
                        {row.body}
                      </Typography>
                    ) : null
                  }
                />
              </ListItemButton>
            ))}
          </List>
        )}
        <Divider />
        <Box sx={{ px: 1, py: 0.75 }}>
          <Button fullWidth size="small" onClick={() => go(ROUTES.notifications)} sx={{ minHeight: 40 }}>
            {t('notifications.viewAll')}
          </Button>
        </Box>
      </Menu>
    </>
  )
}
