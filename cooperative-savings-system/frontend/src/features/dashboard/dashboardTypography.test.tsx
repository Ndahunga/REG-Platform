import { render, screen } from '@testing-library/react'
import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { MetricCard } from '@/shared/components/MetricCard'
import {
  dashboardChartTitleSx,
  dashboardHeroAmountSx,
  dashboardHintSx,
  dashboardKpiLabelSx,
  dashboardKpiValueSx,
  dashboardPageTitleSx,
  dashboardSectionTitleSx,
  dashboardWelcomeTitleSx,
} from './dashboardTypography'

const px = (rem: string) => Number.parseFloat(rem)
const base = (size: string | { xs?: string; sm?: string; md?: string }) =>
  typeof size === 'string' ? size : (size.xs as string)
const top = (size: string | { xs?: string; sm?: string; md?: string }) =>
  typeof size === 'string' ? size : ((size.md ?? size.sm ?? size.xs) as string)

describe('dashboard type scale', () => {
  it('uses the agreed medium sizes', () => {
    expect(dashboardPageTitleSx.fontSize).toEqual({ xs: '1.4rem', md: '1.65rem' })
    expect(dashboardWelcomeTitleSx.fontSize).toEqual({ xs: '1.35rem', md: '1.6rem' })
    expect(dashboardSectionTitleSx.fontSize).toEqual({ xs: '1.05rem', md: '1.15rem' })
    expect(dashboardChartTitleSx.fontSize).toBe('1rem')
    expect(dashboardKpiValueSx.fontSize).toEqual({ xs: '1.15rem', sm: '1.35rem' })
    expect(dashboardHeroAmountSx.fontSize).toEqual({ xs: '1.55rem', md: '1.9rem' })
  })

  it('keeps a clear order: page title > section title > chart title, and the funds amount is the strongest number', () => {
    expect(px(top(dashboardPageTitleSx.fontSize))).toBeGreaterThan(px(top(dashboardSectionTitleSx.fontSize)))
    expect(px(top(dashboardSectionTitleSx.fontSize))).toBeGreaterThan(px(top(dashboardChartTitleSx.fontSize)))
    expect(px(top(dashboardHeroAmountSx.fontSize))).toBeGreaterThan(px(top(dashboardKpiValueSx.fontSize)))
    expect(px(base(dashboardHeroAmountSx.fontSize))).toBeGreaterThan(px(base(dashboardKpiValueSx.fontSize)))
  })

  it('does not use 700 weights and keeps supporting text readable', () => {
    for (const style of [
      dashboardPageTitleSx,
      dashboardWelcomeTitleSx,
      dashboardSectionTitleSx,
      dashboardChartTitleSx,
      dashboardKpiValueSx,
      dashboardKpiLabelSx,
      dashboardHeroAmountSx,
    ]) {
      expect(style.fontWeight).toBeLessThanOrEqual(600)
    }
    expect(px(dashboardHintSx.fontSize)).toBeGreaterThanOrEqual(0.875)
    expect(px(dashboardKpiLabelSx.fontSize)).toBeGreaterThanOrEqual(0.7)
    expect(px(dashboardKpiLabelSx.fontSize)).toBeLessThanOrEqual(0.78)
  })

  it('no dashboard component forces a 700 weight or a different font family any more', () => {
    const dir = 'src/features/dashboard/'
    for (const file of readdirSync(dir).filter((f) => f.endsWith('.tsx') && !f.includes('test'))) {
      const source = readFileSync(dir + file, 'utf8')
      expect(source, file).not.toMatch(/fontWeight: 700/)
      expect(source, file).not.toMatch(/fontFamily/)
    }
  })
})

describe('MetricCard scale', () => {
  /** jsdom ignores media queries, so read the emitted CSS rules for the element's classes instead. */
  const css = (el: HTMLElement) => {
    const classes = [...el.classList]
    return [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .filter((rule) => classes.some((c) => rule.cssText.includes('.' + c)))
      .map((rule) => rule.cssText)
      .join(' ')
  }

  it('renders the same value and label at either scale', () => {
    render(
      <>
        <MetricCard label="Members" value="1,200" scale="dashboard" />
        <MetricCard label="Loans" value="RWF 5,000" />
      </>,
    )
    expect(screen.getByText('1,200')).toBeVisible()
    expect(screen.getByText('Members')).toBeVisible()
    expect(screen.getByText('RWF 5,000')).toBeVisible()
    expect(screen.getByText('Loans')).toBeVisible()
  })

  it('is smaller on dashboards, while other pages keep the original size', () => {
    render(
      <>
        <MetricCard label="Dash" value="111" scale="dashboard" />
        <MetricCard label="Other" value="222" />
      </>,
    )
    const dash = css(screen.getByText('111'))
    expect(dash).toContain('1.15rem')
    expect(dash).toContain('1.35rem')
    expect(dash).toMatch(/font-weight: 600/)
    expect(dash).not.toContain('1.6rem')

    const other = css(screen.getByText('222'))
    expect(other).toContain('1.35rem')
    expect(other).toContain('1.6rem')
    expect(other).toMatch(/font-weight: 700/)
    expect(css(screen.getByText('Dash'))).toContain('0.72rem')
  })
})
