/**
 * Clean text-link style for specific link groups (footer contact links, Forgot password, Register, Login). It is NOT
 * applied globally: other links in the app keep their normal underline.
 *
 * No underline in any state. Hover uses a subtle opacity change (works on light, dark and the always-dark footer);
 * keyboard focus keeps a clearly visible outline.
 */
export const plainLinkSx = {
  textDecoration: 'none',
  cursor: 'pointer',
  borderRadius: '2px',
  transition: 'opacity 150ms ease',
  '&:hover': { textDecoration: 'none', opacity: 0.78 },
  '&:focus-visible': {
    textDecoration: 'none',
    outline: '2px solid currentColor',
    outlineOffset: '3px',
  },
  '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
} as const
