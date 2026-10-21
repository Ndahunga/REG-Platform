import '@testing-library/jest-dom/vitest'
import { vi } from 'vitest'
import '@/i18n'

// jsdom does not implement scrolling; layouts start new pages at the top, so give them a harmless stub.
window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
