import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { WhatsAppShareDialog } from './WhatsAppShareDialog'

describe('WhatsAppShareDialog', () => {
  it('reuses Reports phone validation and blocks send until valid', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    const onPhoneChange = vi.fn()
    const { rerender } = render(
      <WhatsAppShareDialog
        open
        pending={false}
        phone=""
        title="Share schedule via WhatsApp"
        description="Enter the recipient phone"
        sendingLabel="Sending"
        onPhoneChange={onPhoneChange}
        onClose={vi.fn()}
        onSend={onSend}
      />,
    )

    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()

    rerender(
      <WhatsAppShareDialog
        open
        pending={false}
        phone="not-a-phone"
        title="Share schedule via WhatsApp"
        description="Enter the recipient phone"
        sendingLabel="Sending"
        onPhoneChange={onPhoneChange}
        onClose={vi.fn()}
        onSend={onSend}
      />,
    )
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()

    rerender(
      <WhatsAppShareDialog
        open
        pending={false}
        phone="0788123456"
        title="Share schedule via WhatsApp"
        description="Enter the recipient phone"
        sendingLabel="Sending"
        onPhoneChange={onPhoneChange}
        onClose={vi.fn()}
        onSend={onSend}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(onSend).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/wa\.me/i)).not.toBeInTheDocument()
  })
})
