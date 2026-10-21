import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cssFor } from '@/test/cssHelpers'
import { AnalyticsGrid } from './AnalyticsGrid'
import { ANALYTICS_FOUR_COLUMN_MIN, ANALYTICS_TWO_COLUMN_MIN, analyticsCardSx } from './analyticsStyles'
import { DonutCard } from './DonutCard'
import { FineActivityCard } from './FineActivityCard'
import { LoansIssuedVsRepaidChart } from './LoansIssuedVsRepaidChart'

const members = [
  { key: 'a', label: 'Active', value: 5, color: '#16A34A' },
  { key: 'i', label: 'Inactive', value: 0, color: '#64748B' },
]
const mix = [
  { key: 'r', label: 'Regular', value: 133000, color: '#1B4D8C' },
  { key: 's', label: 'Special', value: 0, color: '#7C3AED' },
]
const rf = (v: number) => `RF ${v.toLocaleString('en-US')}`

function precedes(a: Element, b: Element) {
  return Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
}

describe('AnalyticsGrid', () => {
  it('is a 1 / 2 / 4 column grid driven by its own width, with columns that can shrink', () => {
    render(
      <AnalyticsGrid>
        <div data-testid="cell" />
      </AnalyticsGrid>,
    )
    const wrapper = screen.getByTestId('analytics-grid')
    expect(cssFor(wrapper)).toMatch(/container-type: inline-size/)

    const grid = screen.getByTestId('cell').parentElement as HTMLElement
    const css = cssFor(grid)
    expect(css).toMatch(/display: grid/)
    // phones: one column
    expect(css).toMatch(/grid-template-columns: minmax\(0, 1fr\)/)
    // medium: two columns, large: four, measured on the grid itself
    expect(css).toContain(`@container (min-width: ${ANALYTICS_TWO_COLUMN_MIN}px)`)
    expect(css).toContain(`@container (min-width: ${ANALYTICS_FOUR_COLUMN_MIN}px)`)
    expect(css).toMatch(/repeat\(2, minmax\(0, 1fr\)\)/)
    expect(css).toMatch(/repeat\(4, minmax\(0, 1fr\)\)/)
    expect(ANALYTICS_TWO_COLUMN_MIN).toBeLessThan(ANALYTICS_FOUR_COLUMN_MIN)
  })

  it('gives every analytics card a shrinkable, content-driven surface (no fixed height)', () => {
    expect(analyticsCardSx.minWidth).toBe(0)
    expect(JSON.stringify(analyticsCardSx)).not.toMatch(/"(min)?[hH]eight":\s*\d/)
    expect(analyticsCardSx.borderRadius).toBe(2)
  })
})

describe('DonutCard compact structure', () => {
  const renderMembers = () =>
    render(
      <DonutCard
        testId="members"
        title="Members"
        slices={members}
        centerValue="5"
        centerLabel="members"
        formatValue={(v) => String(v)}
        emptyMessage="none"
      />,
    )

  it('puts the donut first and the legend below it', () => {
    renderMembers()
    const donut = screen.getByTestId('members-donut')
    const legend = screen.getByTestId('members-legend')
    expect(precedes(donut, legend)).toBe(true)
    expect(donut.parentElement).toBe(legend.parentElement) // stacked in the same column, not side by side
  })

  it('shows one aligned row per slice: dot, label, value and share', () => {
    renderMembers()
    const rows = within(screen.getByTestId('members-legend')).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0].children).toHaveLength(4)
    expect(rows[0]).toHaveTextContent('Active')
    expect(rows[0]).toHaveTextContent('5')
    expect(rows[0]).toHaveTextContent('100.0%')
    expect(rows[1]).toHaveTextContent('Inactive')
    expect(rows[1]).toHaveTextContent('0.0%')
    // the columns come from one shared grid so they line up across rows
    const legendCss = cssFor(screen.getByTestId('members-legend'))
    expect(legendCss).toMatch(/grid-template-columns: 10px minmax\(0, 1fr\) auto auto/)
    expect(cssFor(rows[0])).toMatch(/subgrid/)
  })

  it('keeps the centre text inside the donut box, with no absolute positioning', () => {
    renderMembers()
    const donut = screen.getByTestId('members-donut')
    const center = screen.getByTestId('members-center')
    expect(donut).toContainElement(center)
    expect(cssFor(donut)).toMatch(/display: grid/)
    expect(cssFor(center)).not.toMatch(/position: absolute/)
    expect(center).toHaveTextContent('5')
    expect(center).toHaveTextContent('members')
    // the donut is smaller than before and centred
    expect(cssFor(donut)).toMatch(/width: 128px/)
    expect(cssFor(donut)).toMatch(/margin-(left|inline)[^;]*auto|margin: [^;]*auto/)
  })

  it('shows the contribution total in the donut and the amounts in the legend, with long values contained', () => {
    render(
      <DonutCard
        testId="mix"
        title="Contribution mix"
        slices={mix}
        centerValue={rf(133000)}
        centerLabel="Total"
        formatValue={rf}
        emptyMessage="none"
      />,
    )
    expect(screen.getByTestId('mix-center')).toHaveTextContent('RF 133,000')
    const rows = within(screen.getByTestId('mix-legend')).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Regular')
    expect(rows[0]).toHaveTextContent('RF 133,000')
    expect(rows[0]).toHaveTextContent('100.0%')
    expect(rows[1]).toHaveTextContent('RF 0')
    // long currency text steps down so it fits the hole, and the centre block is capped to the hole width
    expect(cssFor(screen.getByTestId('mix-center').querySelector('p') as Element)).toMatch(/font-size: 0\.82rem/)
    expect(cssFor(screen.getByTestId('mix-center'))).toMatch(/max-width: 74px/)
  })

  it('stays compact when there is nothing to chart', () => {
    render(
      <DonutCard
        testId="empty"
        title="Members"
        slices={[{ key: 'a', label: 'Active', value: 0, color: '#000' }]}
        centerValue="0"
        centerLabel="members"
        formatValue={(v) => String(v)}
        emptyMessage="No members yet"
      />,
    )
    expect(screen.getByTestId('empty-empty')).toHaveTextContent('No members yet')
    expect(screen.queryByTestId('empty-donut')).not.toBeInTheDocument()
    expect(cssFor(screen.getByTestId('empty-empty'))).not.toMatch(/(min-)?height: \d/)
  })
})

describe('compact empty states for loans and fines', () => {
  it('loans: the empty message and the outstanding principal sit together without a reserved chart area', () => {
    render(<LoansIssuedVsRepaidChart issuedAmount={0} repaidAmount={0} outstandingPrincipal={1600000} currency="RWF" />)
    const empty = screen.getByTestId('loans-issued-vs-repaid-empty')
    expect(empty).toHaveTextContent('No loans issued or repaid this month yet')
    expect(cssFor(empty)).not.toMatch(/(?<![-\w])height: \d/)
    const callout = screen.getByTestId('loan-outstanding-callout')
    expect(callout).toHaveTextContent(/1[,\s]?600[,\s]?000/)
    expect(precedes(empty, callout)).toBe(true)
    expect(cssFor(callout)).toMatch(/border-top-width: 1px/)
  })

  it('fines: the empty message is compact and the summary line stays in the card', () => {
    render(
      <FineActivityCard
        currency="RWF"
        fines={
          { issuedCountCurrentMonth: 0, issuedAmountCurrentMonth: 0, collectedCurrentMonth: 0, collectedPreviousMonth: 0 } as never
        }
      />,
    )
    const empty = screen.getByTestId('fine-activity-empty')
    expect(cssFor(empty)).not.toMatch(/(?<![-\w])height: \d/)
    expect(screen.getByTestId('fine-activity-card')).toContainElement(screen.getByTestId('fines-issued-value'))
  })

  it('uses the same compact card surface as the donut cards', () => {
    render(
      <>
        <LoansIssuedVsRepaidChart issuedAmount={0} repaidAmount={0} currency="RWF" />
        <FineActivityCard currency="RWF" fines={{ issuedCountCurrentMonth: 0, issuedAmountCurrentMonth: 0, collectedCurrentMonth: 0, collectedPreviousMonth: 0 } as never} />
      </>,
    )
    for (const id of ['loans-issued-vs-repaid-chart', 'fine-activity-card']) {
      expect(cssFor(screen.getByTestId(id))).toMatch(/min-width: 0/)
    }
  })
})
