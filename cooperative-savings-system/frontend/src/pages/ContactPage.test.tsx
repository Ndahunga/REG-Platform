import { ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { sendContactMessage } from '@/shared/api/contact'
import { cssFor } from '@/test/cssHelpers'
import { lightTheme } from '@/theme/theme'
import { ContactPage } from './ContactPage'

vi.mock('@/shared/api/contact', () => ({ sendContactMessage: vi.fn() }))

function deferred<T = void>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false }, queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider theme={lightTheme}>
        <MemoryRouter>
          <ContactPage />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

const field = (label: RegExp) => screen.getByLabelText(label)
const send = () => screen.getByRole('button', { name: /Send message|Sending/ })

function fill(values: Partial<Record<'first' | 'last' | 'email' | 'phone' | 'message', string>> = {}) {
  const v = {
    first: 'Alice',
    last: 'Uwase',
    email: 'alice@example.com',
    phone: '',
    message: 'I need help setting up my scheme.',
    ...values,
  }
  fireEvent.change(field(/^First name/i), { target: { value: v.first } })
  fireEvent.change(field(/^Last name/i), { target: { value: v.last } })
  fireEvent.change(field(/^Email/i), { target: { value: v.email } })
  if (v.phone) fireEvent.change(field(/Phone number/i), { target: { value: v.phone } })
  fireEvent.change(field(/^Message/i), { target: { value: v.message } })
}

describe('contact page content', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the title, intro and the compact form, with no top contact-information card', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: 'Contact OuWealth Community' })).toBeInTheDocument()
    expect(screen.getByText(/Questions about OuWealth/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Send us a message' })).toBeInTheDocument()
    expect(screen.getByTestId('contact-form')).toBeInTheDocument()

    // the Call us / WhatsApp / Email list is gone from this page
    expect(screen.queryByTestId('support-methods')).not.toBeInTheDocument()
    expect(screen.queryByTestId('support-call')).not.toBeInTheDocument()
    expect(screen.queryByText('Call us')).not.toBeInTheDocument()
    expect(screen.queryByText('0782102154')).not.toBeInTheDocument()
    expect(screen.queryByText('support@ozufy.com')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^tel:/ })).not.toBeInTheDocument()
  })

  it('keeps the Chat on WhatsApp button inside the form, opening the support chat in a new tab', () => {
    renderPage()
    const chat = within(screen.getByTestId('contact-form')).getByRole('link', { name: 'Chat on WhatsApp' })
    expect(chat.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/250793634217\?text=/)
    expect(decodeURIComponent(chat.getAttribute('href')!.split('?text=')[1])).toMatch(/^Hello OuWealth Support/)
    expect(chat).toHaveAttribute('target', '_blank')
    expect(chat.getAttribute('rel')).toMatch(/noopener/)
  })

  it('has first/last name, email, country code (Rwanda by default), phone and message', () => {
    renderPage()
    for (const label of [/^First name/i, /^Last name/i, /^Email/i, /Phone number/i, /^Message/i]) {
      expect(field(label)).toBeInTheDocument()
    }
    expect(screen.getByRole('combobox', { name: /Country code/i })).toHaveTextContent('RW (+250)')
  })

  it('keeps one compact centred form column of about 560px', () => {
    renderPage()
    expect(cssFor(screen.getByTestId('contact-column'))).toMatch(/max-width: 608px/)
    const card = cssFor(screen.getByTestId('contact-form-card'))
    expect(card).toMatch(/max-width: 560px/)
    expect(card).toMatch(/width: 100%/)
  })

  it('keeps the fields full width and the actions content-sized and grouped on the right', () => {
    renderPage()
    const card = screen.getByTestId('contact-form-card')
    for (const label of [/^Email/i, /^Message/i]) {
      expect(within(card).getByLabelText(label).closest('.MuiFormControl-root')).toHaveClass('MuiFormControl-fullWidth')
    }
    const actions = screen.getByTestId('contact-actions')
    expect(cssFor(actions)).toMatch(/justify-content: flex-end/)
    for (const el of [send(), screen.getByRole('link', { name: 'Chat on WhatsApp' })]) {
      expect(el).not.toHaveClass('MuiButton-fullWidth')
      expect(cssFor(el)).toMatch(/width: auto/)
    }
  })
})

describe('contact form validation', () => {
  beforeEach(() => vi.clearAllMocks())

  it('requires first and last name', async () => {
    const user = userEvent.setup()
    renderPage()
    fill({ first: '   ', last: '' })
    await user.click(send())
    expect(await screen.findByText('First name is required')).toBeInTheDocument()
    expect(screen.getByText('Last name is required')).toBeInTheDocument()
    expect(sendContactMessage).not.toHaveBeenCalled()
  })

  it('requires an email and rejects an invalid one', async () => {
    const user = userEvent.setup()
    renderPage()
    fill({ email: '' })
    await user.click(send())
    expect(await screen.findByText('Email is required')).toBeInTheDocument()

    fill({ email: 'not-an-email' })
    await user.click(send())
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(sendContactMessage).not.toHaveBeenCalled()
  })

  it('requires a message and caps its length', async () => {
    const user = userEvent.setup()
    renderPage()
    fill({ message: '  ' })
    await user.click(send())
    expect(await screen.findByText('Message is required')).toBeInTheDocument()

    fill({ message: 'm'.repeat(1001) })
    await user.click(send())
    expect(await screen.findByText('This is too long')).toBeInTheDocument()
    expect(sendContactMessage).not.toHaveBeenCalled()
  })

  it('treats the phone as optional but rejects an invalid Rwandan number', async () => {
    const user = userEvent.setup()
    renderPage()
    fill({ phone: '12345' })
    await user.click(send())
    expect(await screen.findByText('Enter a Rwandan mobile number (07XXXXXXXX)')).toBeInTheDocument()
    expect(sendContactMessage).not.toHaveBeenCalled()
  })
})

describe('contact form submission goes to the backend', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(sendContactMessage).mockResolvedValue(undefined)
  })

  it('posts the validated, trimmed fields to the API once, with no recipient', async () => {
    const user = userEvent.setup()
    renderPage()
    fill({ first: ' Alice ', last: 'Uwase', email: ' alice@example.com ', phone: '078 123 4567', message: ' Hello & welcome ' })
    await user.click(send())

    await waitFor(() => expect(sendContactMessage).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(sendContactMessage).mock.calls[0][0]
    expect(payload).toEqual({
      firstName: 'Alice',
      lastName: 'Uwase',
      email: 'alice@example.com',
      countryCode: '+250',
      phoneNumber: '0781234567',
      message: 'Hello & welcome',
    })
    expect(Object.keys(payload)).not.toEqual(expect.arrayContaining(['to', 'recipient', 'cc', 'bcc']))
  })

  it('leaves the phone out when none was given', async () => {
    const user = userEvent.setup()
    renderPage()
    fill()
    await user.click(send())
    await waitFor(() => expect(sendContactMessage).toHaveBeenCalledTimes(1))
    expect(vi.mocked(sendContactMessage).mock.calls[0][0]).not.toHaveProperty('phoneNumber')
  })

  it('does not use a mailto link: sending is a backend call', async () => {
    const user = userEvent.setup()
    renderPage()
    fill()
    await user.click(send())
    await waitFor(() => expect(sendContactMessage).toHaveBeenCalledTimes(1))
    for (const link of screen.queryAllByRole('link')) {
      expect(link.getAttribute('href') ?? '').not.toMatch(/^mailto:/)
    }
    expect(screen.getByRole('button', { name: 'Send message' })).toHaveAttribute('type', 'submit')
  })

  it('disables Send message and shows a loading state while sending, and ignores a second submit', async () => {
    const user = userEvent.setup()
    const pending = deferred()
    vi.mocked(sendContactMessage).mockReturnValue(pending.promise)
    renderPage()
    fill()
    await user.click(send())

    const button = await screen.findByRole('button', { name: /Sending/ })
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('Sending...')
    expect(within(button).getByRole('progressbar')).toBeInTheDocument()
    fireEvent.submit(screen.getByTestId('contact-form'))
    expect(sendContactMessage).toHaveBeenCalledTimes(1)

    pending.resolve()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Send message' })).toBeEnabled())
  })

  it('on success shows the confirmation and clears the form', async () => {
    const user = userEvent.setup()
    renderPage()
    fill({ phone: '0781234567' })
    await user.click(send())

    expect(await screen.findByTestId('contact-sent')).toHaveTextContent(
      'Your message has been sent to the OuWealth support team.',
    )
    expect(screen.queryByTestId('contact-failed')).not.toBeInTheDocument()
    await waitFor(() => expect(field(/^First name/i)).toHaveValue(''))
    expect(field(/^Last name/i)).toHaveValue('')
    expect(field(/^Email/i)).toHaveValue('')
    expect(field(/Phone number/i)).toHaveValue('')
    expect(field(/^Message/i)).toHaveValue('')
    expect(screen.getByRole('combobox', { name: /Country code/i })).toHaveTextContent('RW (+250)')
  })

  it('on failure keeps what was typed, shows a friendly error and does not claim it was sent', async () => {
    const user = userEvent.setup()
    vi.mocked(sendContactMessage).mockRejectedValue(new Error('Request failed with status code 503'))
    renderPage()
    fill({ phone: '0781234567' })
    await user.click(send())

    expect(await screen.findByTestId('contact-failed')).toHaveTextContent(
      "We couldn't send your message. Please try again or contact us by email or WhatsApp.",
    )
    expect(screen.queryByTestId('contact-sent')).not.toBeInTheDocument()
    expect(screen.queryByText(/has been sent/i)).not.toBeInTheDocument()
    expect(screen.getByText(/couldn't send/)).not.toHaveTextContent(/503|status code/)
    expect(field(/^First name/i)).toHaveValue('Alice')
    expect(field(/^Last name/i)).toHaveValue('Uwase')
    expect(field(/^Email/i)).toHaveValue('alice@example.com')
    expect(field(/Phone number/i)).toHaveValue('0781234567')
    expect(field(/^Message/i)).toHaveValue('I need help setting up my scheme.')
    expect(screen.getByRole('button', { name: 'Send message' })).toBeEnabled()
  })

  it('can be retried after a failure and then succeeds', async () => {
    const user = userEvent.setup()
    vi.mocked(sendContactMessage).mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(undefined)
    renderPage()
    fill()
    await user.click(send())
    expect(await screen.findByTestId('contact-failed')).toBeInTheDocument()

    await user.click(send())
    expect(await screen.findByTestId('contact-sent')).toBeInTheDocument()
    expect(screen.queryByTestId('contact-failed')).not.toBeInTheDocument()
    expect(sendContactMessage).toHaveBeenCalledTimes(2)
  })

  it('stays fully usable on a phone-width viewport', async () => {
    const user = userEvent.setup()
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 375 })
    window.dispatchEvent(new Event('resize'))
    renderPage()
    const names = within(screen.getByTestId('contact-form'))
      .getAllByRole('textbox')
      .map((el) => el.getAttribute('name'))
    expect(names).toEqual(['firstName', 'lastName', 'email', 'phone', 'message'])
    fill()
    await user.click(send())
    await waitFor(() => expect(sendContactMessage).toHaveBeenCalledTimes(1))
  })
})
