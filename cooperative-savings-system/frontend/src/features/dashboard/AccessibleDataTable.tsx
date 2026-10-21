import { Box, Link } from '@mui/material'
import type { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router-dom'

export interface AccessibleDataRow {
  key: string
  cells: ReactNode[]
}

interface AccessibleDataTableProps {
  caption: string
  columns: string[]
  rows: AccessibleDataRow[]
}

/**
 * The text alternative for a chart: every plotted value as a real table. Visually hidden until a
 * keyboard user focuses a link inside it, so charts stay clean while screen readers (and tests) get
 * the exact names, values and links.
 */
export function AccessibleDataTable({ caption, columns, rows }: AccessibleDataTableProps) {
  return (
    <Box sx={{ position: 'relative' }}>
      <Box
        sx={{
          position: 'absolute',
          width: '1px',
          height: '1px',
          m: '-1px',
          p: 0,
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
          whiteSpace: 'nowrap',
          border: 0,
          '&:focus-within': {
            position: 'static',
            width: 'auto',
            height: 'auto',
            m: 0,
            mt: 1,
            overflow: 'visible',
            clip: 'auto',
            whiteSpace: 'normal',
          },
        }}
      >
        <table aria-label={caption} style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column} scope="col" style={{ textAlign: 'left', padding: '2px 8px' }}>
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key}>
                {row.cells.map((cell, index) =>
                  index === 0 ? (
                    <th key={index} scope="row" style={{ textAlign: 'left', padding: '2px 8px' }}>
                      {cell}
                    </th>
                  ) : (
                    <td key={index} style={{ padding: '2px 8px' }}>
                      {cell}
                    </td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </Box>
    </Box>
  )
}

/** Member name as a link when the viewer may open the record, otherwise plain text. */
export function MemberNameCell({ label, href }: { label: string; href?: string }) {
  if (!href) return <>{label}</>
  return (
    <Link component={RouterLink} to={href} underline="hover" sx={{ fontWeight: 600 }}>
      {label}
    </Link>
  )
}
