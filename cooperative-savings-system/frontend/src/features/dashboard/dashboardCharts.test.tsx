import { ThemeProvider } from '@mui/material'
import { render, screen, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { lightTheme } from '@/theme/theme'
import { CHART_COLORS } from './chartPalette'
import type { RankedBarDatum, ReliabilityBarDatum } from './dashboardVisuals'
import { DonutCard } from './DonutCard'
import { RankedBarChart } from './RankedBarChart'
import { StackedShareBarChart } from './StackedShareBarChart'

function renderWithProviders(ui: ReactElement) {
  return render(
    <ThemeProvider theme={lightTheme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </ThemeProvider>,
  )
}

const money = (value: number) => `RWF ${value.toLocaleString('en-US')}`

describe('DonutCard', () => {
  it('shows each slice with its value and share in the legend, and the total in the center', () => {
    renderWithProviders(
      <DonutCard
        testId="donut"
        title="Members"
        slices={[
          { key: 'active', label: 'Active', value: 24, color: CHART_COLORS.green },
          { key: 'inactive', label: 'Inactive', value: 6, color: CHART_COLORS.neutral },
        ]}
        centerValue="30"
        centerLabel="members"
        formatValue={(v) => String(v)}
        emptyMessage="No members yet."
      />,
    )
    const card = screen.getByTestId('donut')
    expect(within(card).getByText('Members')).toBeInTheDocument()
    expect(within(card).getByText('30')).toBeInTheDocument()
    expect(within(card).getByText('Active')).toBeInTheDocument()
    expect(within(card).getByText('24')).toBeInTheDocument()
    expect(within(card).getByText('80.0%')).toBeInTheDocument()
    expect(within(card).getByText('Inactive')).toBeInTheDocument()
    expect(within(card).getByText('6')).toBeInTheDocument()
    expect(within(card).getByText('20.0%')).toBeInTheDocument()
    expect(screen.queryByTestId('donut-empty')).not.toBeInTheDocument()
  })

  it('shows an empty state instead of an empty ring when the total is zero', () => {
    renderWithProviders(
      <DonutCard
        testId="donut"
        title="Contribution mix"
        slices={[
          { key: 'regular', label: 'Regular', value: 0, color: CHART_COLORS.blue },
          { key: 'special', label: 'Special', value: 0, color: CHART_COLORS.purple },
        ]}
        centerValue={money(0)}
        centerLabel="Total"
        formatValue={money}
        emptyMessage="No contributions recorded yet."
      />,
    )
    expect(screen.getByTestId('donut-empty')).toHaveTextContent('No contributions recorded yet.')
    expect(screen.getByTestId('donut').textContent).not.toMatch(/NaN|Infinity/)
    expect(screen.queryByText('Regular')).not.toBeInTheDocument()
  })

  it('treats NaN, Infinity and negative slice values as zero without printing them', () => {
    renderWithProviders(
      <DonutCard
        testId="donut"
        title="Mix"
        slices={[
          { key: 'a', label: 'A', value: 10, color: CHART_COLORS.blue },
          { key: 'b', label: 'B', value: Number.NaN, color: CHART_COLORS.purple },
          { key: 'c', label: 'C', value: Infinity, color: CHART_COLORS.red },
          { key: 'd', label: 'D', value: -5, color: CHART_COLORS.orange },
        ]}
        centerValue="10"
        centerLabel="total"
        formatValue={(v) => String(v)}
        emptyMessage="empty"
      />,
    )
    const card = screen.getByTestId('donut')
    expect(card.textContent).not.toMatch(/NaN|Infinity|-5/)
    expect(within(card).getByText('100.0%')).toBeInTheDocument()
  })

  it('renders a loading placeholder instead of values while loading', () => {
    renderWithProviders(
      <DonutCard
        testId="donut"
        title="Members"
        loading
        slices={[{ key: 'a', label: 'Active', value: 5, color: CHART_COLORS.green }]}
        centerValue="5"
        centerLabel="members"
        formatValue={(v) => String(v)}
        emptyMessage="empty"
      />,
    )
    expect(screen.queryByText('Active')).not.toBeInTheDocument()
    expect(screen.queryByTestId('donut-empty')).not.toBeInTheDocument()
  })
})

describe('RankedBarChart', () => {
  const data: RankedBarDatum[] = [
    { id: 'm1', rank: 1, label: 'Diana', value: 450000, href: '/members/m1', meta: { fineCount: 4, issuedAmount: 50000 } },
    { id: 'm2', rank: 2, label: 'Caleb', value: 390000, meta: { fineCount: 1, issuedAmount: 10000 } },
  ]

  function renderChart(rows = data) {
    return renderWithProviders(
      <RankedBarChart
        data={rows}
        ariaLabel="Highest outstanding fines"
        nameLabel="Member"
        valueLabel="Outstanding fines"
        formatValue={money}
        detailColumns={[
          { key: 'fineCount', label: 'Fines' },
          { key: 'issuedAmount', label: 'Issued', format: (value) => money(Number(value)) },
        ]}
      />,
    )
  }

  it('exposes every plotted name, value and detail as an accessible table', () => {
    renderChart()
    const table = screen.getByRole('table', { name: 'Highest outstanding fines' })
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Member',
      'Rank',
      'Outstanding fines',
      'Fines',
      'Issued',
    ])
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(3) // header + 2 members
    expect(within(rows[1]).getByText('Diana')).toBeInTheDocument()
    expect(within(rows[1]).getByText('RWF 450,000')).toBeInTheDocument()
    expect(within(rows[1]).getByText('4')).toBeInTheDocument()
    expect(within(rows[1]).getByText('RWF 50,000')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Caleb')).toBeInTheDocument()
    expect(within(rows[2]).getByText('RWF 390,000')).toBeInTheDocument()
  })

  it('links a member only when an href is provided', () => {
    renderChart()
    expect(screen.getByRole('link', { name: 'Diana' })).toHaveAttribute('href', '/members/m1')
    expect(screen.queryByRole('link', { name: 'Caleb' })).not.toBeInTheDocument()
    expect(screen.getByText('Caleb')).toBeInTheDocument()
  })

  it('never prints NaN or Infinity', () => {
    renderChart([{ id: 'x', rank: 1, label: 'Zed', value: 0, meta: {} }])
    expect(screen.getByRole('table').textContent).not.toMatch(/NaN|Infinity/)
    expect(screen.getByText('RWF 0')).toBeInTheDocument()
  })
})

describe('StackedShareBarChart', () => {
  const bar = (over: Partial<ReliabilityBarDatum>): ReliabilityBarDatum => ({
    id: 'm1',
    label: 'Jane Doe',
    onTime: 9,
    paidLate: 2,
    pastDue: 1,
    evaluated: 12,
    onTimePct: 75,
    paidLatePct: 16.7,
    pastDuePct: 8.3,
    onTimeRate: 75,
    ...over,
  })

  it('shows the legend and keeps rate and counts available as a table', () => {
    renderWithProviders(
      <StackedShareBarChart
        data={[bar({ href: '/members/m1' }), bar({ id: 'm2', label: 'Eric N.', onTimeRate: null, onTime: 0, paidLate: 0, pastDue: 0, evaluated: 0 })]}
        ariaLabel="Repayment Reliability"
      />,
    )
    const legend = screen.getByTestId('repayment-reliability-legend')
    expect(within(legend).getByText('On time')).toBeInTheDocument()
    expect(within(legend).getByText('Late')).toBeInTheDocument()
    expect(within(legend).getByText('Past due')).toBeInTheDocument()

    const table = screen.getByRole('table', { name: 'Repayment Reliability' })
    const rows = within(table).getAllByRole('row')
    expect(within(rows[1]).getByRole('link', { name: 'Jane Doe' })).toHaveAttribute('href', '/members/m1')
    expect(within(rows[1]).getByText('75.0%')).toBeInTheDocument()
    expect(within(rows[1]).getByText('9')).toBeInTheDocument()
    expect(within(rows[1]).getByText('12')).toBeInTheDocument()
    // a missing rate is shown as an em dash, not NaN
    expect(within(rows[2]).getByText('—')).toBeInTheDocument()
    expect(table.textContent).not.toMatch(/NaN|Infinity/)
  })
})
