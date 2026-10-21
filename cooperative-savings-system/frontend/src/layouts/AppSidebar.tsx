import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import PushPinIcon from '@mui/icons-material/PushPin'
import PushPinOutlinedIcon from '@mui/icons-material/PushPinOutlined'
import {
  Badge,
  Box,
  Collapse,
  Divider,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Toolbar,
  Tooltip,
} from '@mui/material'
import { useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, useLocation } from 'react-router-dom'
import { BrandLogo } from '@/shared/components/BrandLogo'
import { ROUTES } from '@/shared/constants/routes'
import {
  getSidebarNavGroups,
  isNavItemActive,
  type AppSidebarSection,
  type NavItem,
  type SidebarNavGroup,
} from './navItems'

export const APP_SIDEBAR_WIDTH = 260

/** Desktop-only pin control rendered in the sidebar header. Never passed on mobile. */
export interface SidebarPinControl {
  pinned: boolean
  onToggle: () => void
}

interface AppSidebarProps {
  userRoles: string[]
  unreadCount?: number
  /** When true, use on-dark brand treatment (permanent dark sidebar chrome). */
  onDarkBrand?: boolean
  /** Invoked when a nav link is activated (e.g. close mobile drawer). */
  onNavigate?: () => void
  /** Desktop pin/unpin button. Omitted on mobile, where the drawer is always temporary. */
  pinControl?: SidebarPinControl
  /** Replaces the role-derived Saving Scheme groups (used by the system-administration portal). */
  groups?: SidebarNavGroup[]
  /** Where the logo links to. Defaults to the Saving Scheme dashboard. */
  homePath?: string
  /** Optional block shown under the logo (e.g. the open Saving Scheme in the support workspace). */
  header?: ReactNode
  /**
   * Saving Scheme layout: a plain Dashboard link followed by collapsible sections. When set, `groups` is
   * ignored. The Super Admin portal keeps the flat grouped list.
   */
  sections?: { dashboard: NavItem; sections: AppSidebarSection[] }
  /** Extra content at the end of the scrolling navigation area (e.g. mobile language/theme controls). */
  navExtras?: ReactNode
  /** Content anchored below the scrolling navigation area (e.g. the account menu and sign out). */
  footer?: ReactNode
}

export function AppSidebar({
  userRoles,
  unreadCount = 0,
  onDarkBrand = false,
  onNavigate,
  pinControl,
  groups: groupsOverride,
  homePath = ROUTES.dashboard,
  header,
  sections,
  navExtras,
  footer,
}: AppSidebarProps) {
  const { t } = useTranslation()
  const location = useLocation()
  const groups = groupsOverride ?? getSidebarNavGroups(userRoles)
  const activeSectionId = sections
    ? sections.sections.find((section) =>
        section.items.some((item) => isNavItemActive(location.pathname, location.search, item.path)),
      )?.id
    : undefined
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() =>
    activeSectionId ? { [activeSectionId]: true } : {},
  )

  // Navigating (or deep-linking) into a collapsed section opens it; other sections keep their state.
  useEffect(() => {
    if (!activeSectionId) return
    setExpanded((prev) => (prev[activeSectionId] ? prev : { ...prev, [activeSectionId]: true }))
  }, [activeSectionId, location.pathname, location.search])

  const toggleSection = (id: string) => setExpanded((prev) => ({ ...prev, [id]: !prev[id] }))

  const renderItem = (item: NavItem) => {
    const Icon = item.icon
    const selected = item.end
      ? location.pathname === item.path
      : isNavItemActive(location.pathname, location.search, item.path)
    const showBadge = item.path === ROUTES.notifications && unreadCount > 0

    return (
      <ListItemButton
        key={`${item.labelKey}-${item.path}`}
        // Plain Link (not NavLink): NavLink marks every link to the same pathname as current, ignoring ?tab=,
        // which would announce Contributions and both approval views as current at once.
        component={Link}
        to={item.path}
        selected={selected}
        aria-current={selected ? 'page' : undefined}
        onClick={() => onNavigate?.()}
        data-testid={`nav-item-${item.path.split('?')[0].replace(/^\//, '') || 'root'}${
          item.path.includes('?') ? `-${item.path.split('?')[1]}` : ''
        }`}
        sx={{
          borderRadius: 2,
          mb: 0.25,
          minHeight: 44,
          mx: 1,
          '&.Mui-selected': {
            bgcolor: 'action.selected',
            fontWeight: 700,
            borderLeft: 3,
            borderColor: 'primary.main',
            pl: 1.25,
          },
          '&.Mui-selected .MuiListItemIcon-root': {
            color: 'primary.main',
          },
          '&.Mui-selected .MuiListItemText-primary': {
            fontWeight: 700,
          },
        }}
      >
        <ListItemIcon sx={{ minWidth: 40 }}>
          {showBadge ? (
            <Badge color="error" badgeContent={unreadCount > 99 ? '99+' : unreadCount}>
              <Icon fontSize="small" />
            </Badge>
          ) : (
            <Icon fontSize="small" />
          )}
        </ListItemIcon>
        <ListItemText primary={t(item.labelKey)} />
      </ListItemButton>
    )
  }

  return (
    <Box
      component="nav"
      aria-label={t('nav.sidebarAria')}
      data-testid="app-sidebar"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        bgcolor: 'background.paper',
        borderRight: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Toolbar
        sx={{
          px: 2,
          minHeight: 68,
          justifyContent: 'space-between',
          gap: 1,
        }}
      >
        <Box
          component={NavLink}
          to={homePath}
          aria-label={t('app.name')}
          sx={{
            display: 'inline-flex',
            textDecoration: 'none',
            color: 'inherit',
            minWidth: 'max-content',
            flexShrink: 0,
          }}
        >
          <BrandLogo variant="lockup" size={40} onDark={onDarkBrand} />
        </Box>
        {pinControl ? (
          <Tooltip title={t(pinControl.pinned ? 'nav.unpinSidebar' : 'nav.pinSidebar')}>
            <IconButton
              size="small"
              onClick={pinControl.onToggle}
              aria-label={t(pinControl.pinned ? 'nav.unpinSidebar' : 'nav.pinSidebar')}
              data-testid="sidebar-pin-toggle"
              sx={{ flexShrink: 0, color: pinControl.pinned ? 'primary.main' : 'text.secondary' }}
            >
              {pinControl.pinned ? <PushPinIcon fontSize="small" /> : <PushPinOutlinedIcon fontSize="small" />}
            </IconButton>
          </Tooltip>
        ) : null}
      </Toolbar>
      <Divider />
      {header}
      <Box data-testid="sidebar-scroll" sx={{ flex: 1, minHeight: 0, overflowY: 'auto', py: 1 }}>
        {sections ? (
          <>
            <List dense sx={{ mb: 0.5 }}>
              {renderItem(sections.dashboard)}
            </List>
            {sections.sections.map((section) => {
              const open = Boolean(expanded[section.id])
              const SectionIcon = section.icon
              const containsActive = section.id === activeSectionId
              const headerId = `sidebar-section-${section.id}`
              const panelId = `${headerId}-items`
              return (
                <List
                  key={section.id}
                  dense
                  disablePadding
                  sx={{ mb: 0.5 }}
                  data-testid={`sidebar-section-${section.id}`}
                >
                  <ListItemButton
                    id={headerId}
                    onClick={() => toggleSection(section.id)}
                    aria-expanded={open}
                    aria-controls={panelId}
                    data-testid={`sidebar-section-toggle-${section.id}`}
                    sx={{ borderRadius: 2, mx: 1, minHeight: 44 }}
                  >
                    <ListItemIcon sx={{ minWidth: 40, color: containsActive ? 'primary.main' : undefined }}>
                      <SectionIcon fontSize="small" />
                    </ListItemIcon>
                    <ListItemText
                      primary={t(section.labelKey)}
                      slotProps={{ primary: { sx: { fontWeight: containsActive ? 700 : 600 } } }}
                    />
                    {open ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
                  </ListItemButton>
                  <Collapse in={open} timeout="auto" id={panelId} role="region" aria-labelledby={headerId}>
                    <List dense disablePadding sx={{ pl: 1 }}>
                      {section.items.map(renderItem)}
                    </List>
                  </Collapse>
                </List>
              )
            })}
          </>
        ) : (
          groups.map((group) => (
            <List
              key={group.id}
              dense
              subheader={
                group.id === 'overview' && !groupsOverride ? undefined : (
                  <ListSubheader
                    component="div"
                    disableSticky
                    sx={{
                      bgcolor: 'transparent',
                      lineHeight: 2,
                      typography: 'overline',
                      color: 'text.secondary',
                      px: 2.5,
                    }}
                  >
                    {t(group.labelKey)}
                  </ListSubheader>
                )
              }
              sx={{ mb: 0.5 }}
            >
              {group.items.map(renderItem)}
            </List>
          ))
        )}
        {navExtras}
      </Box>
      {footer}
    </Box>
  )
}
