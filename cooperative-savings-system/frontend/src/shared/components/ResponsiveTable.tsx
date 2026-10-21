import {
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  useMediaQuery,
  useTheme,
  type SxProps,
  type Theme,
} from '@mui/material'
import type { ReactNode } from 'react'
import { EmptyState } from './EmptyState'

export interface TableColumn<T> {
  id: string
  label: string
  render: (row: T) => ReactNode
  hideOnMobile?: boolean
}

interface ResponsiveTableProps<T> {
  columns: TableColumn<T>[]
  rows: T[]
  getRowId: (row: T) => string
  emptyTitle?: string
  emptyDescription?: string
  phase?: number | string
  onRowClick?: (row: T) => void
  getRowSx?: (row: T) => SxProps<Theme> | undefined
}

export function ResponsiveTable<T>({
  columns,
  rows,
  getRowId,
  emptyTitle,
  emptyDescription,
  phase,
  onRowClick,
  getRowSx,
}: ResponsiveTableProps<T>) {
  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'))

  if (rows.length === 0) {
    return (
      <EmptyState title={emptyTitle} description={emptyDescription} phase={phase} />
    )
  }

  if (isMobile) {
    return (
      <>
        {rows.map((row) => (
          <Paper
            key={getRowId(row)}
            elevation={0}
            onClick={onRowClick ? () => onRowClick(row) : undefined}
            sx={{
              p: 2,
              mb: 1.5,
              border: '1px solid',
              borderColor: 'divider',
              cursor: onRowClick ? 'pointer' : 'default',
              '&:hover': onRowClick ? { borderColor: 'primary.light' } : undefined,
              minHeight: 48,
              ...((getRowSx?.(row) as object) ?? {}),
            }}
          >
            {columns
              .filter((column) => !column.hideOnMobile)
              .map((column) => (
              <div key={column.id} style={{ marginBottom: 8 }}>
                <Typography variant="caption" color="text.secondary">
                  {column.label}
                </Typography>
                <Typography variant="body2" component="div" sx={{ wordBreak: 'break-word' }}>
                  {column.render(row)}
                </Typography>
              </div>
            ))}
          </Paper>
        ))}
      </>
    )
  }

  const visibleColumns = columns.filter((c) => !c.hideOnMobile || !isMobile)

  return (
    <TableContainer
      component={Paper}
      elevation={0}
      sx={{ border: '1px solid', borderColor: 'divider', overflowX: 'auto' }}
    >
      <Table size="small" sx={{ minWidth: 640 }}>
        <TableHead>
          <TableRow>
            {visibleColumns.map((column) => (
              <TableCell key={column.id}>{column.label}</TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((row) => (
            <TableRow
              key={getRowId(row)}
              hover
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              sx={{
                cursor: onRowClick ? 'pointer' : 'default',
                ...((getRowSx?.(row) as object) ?? {}),
              }}
            >
              {visibleColumns.map((column) => (
                <TableCell key={column.id}>{column.render(row)}</TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
