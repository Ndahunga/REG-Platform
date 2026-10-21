import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteRotationConfiguration, fetchRotationConfigurations } from '@/shared/api/rotation'
import { renderWithApp } from '@/test/paymentTestHarness'
import { RotationConfigPanel } from './RotationConfigPanel'

vi.mock('@/shared/api/rotation', () => ({
  fetchRotationConfigurations: vi.fn(),
  createRotationConfiguration: vi.fn(),
  deleteRotationConfiguration: vi.fn(),
}))

const PRESIDENT = { id: 'pres', roles: ['PRESIDENT'], permissions: ['COOPERATIVE_WRITE'] }

describe('rotation configuration removal', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(deleteRotationConfiguration).mockResolvedValue()
    vi.mocked(fetchRotationConfigurations).mockResolvedValue([
      { id: 'future', effectiveYear: 2027, effectiveMonth: 3, rotatingBps: 4000, retainedBps: 6000, canDelete: true, deletionBlockedReason: null },
      { id: 'used', effectiveYear: 2026, effectiveMonth: 11, rotatingBps: 5000, retainedBps: 5000, canDelete: false, deletionBlockedReason: 'ROTATION_CONFIG_IN_USE' },
    ])
  })

  it('offers Delete only on a configuration the backend says nobody relied on, and explains the others', async () => {
    renderWithApp(<RotationConfigPanel cooperativeId="c1" model="HYBRID" canManage />, { user: PRESIDENT })
    await screen.findAllByTestId('rotation-config-row')
    expect(screen.getAllByRole('button', { name: /^Delete the configuration of/ })).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Delete the configuration of 2027-03' })).toBeInTheDocument()
    expect(screen.getByText(/Contributions were split under it: it is history/)).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Delete the configuration of 2027-03' }))
    expect(await screen.findByText('Delete configuration?')).toBeInTheDocument()
    expect(deleteRotationConfiguration).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Delete Configuration' }))
    await waitFor(() => expect(deleteRotationConfiguration).toHaveBeenCalledWith('c1', 'future'))
  })

  it('shows no Delete to someone who cannot manage the scheme', async () => {
    renderWithApp(<RotationConfigPanel cooperativeId="c1" model="HYBRID" canManage={false} />, { user: { id: 'm', roles: ['MEMBER'], permissions: [] } })
    await screen.findAllByTestId('rotation-config-row')
    expect(screen.queryByRole('button', { name: /^Delete the configuration of/ })).not.toBeInTheDocument()
  })
})
