/** Overall width of the auth card on desktop: two equal halves of about 450px. */
export const AUTH_CARD_MAX_WIDTH = 900

/** Width of the actual auth form content (login, signup and every wizard step), centered inside its half. */
export const AUTH_FORM_MAX_WIDTH = 360

/**
 * Auth action buttons (Sign in, Back, Next, Create) are sized to their content with a sensible minimum, never
 * stretched across the form.
 */
export const AUTH_ACTION_BUTTON_SX = {
  width: 'auto',
  minWidth: 120,
  minHeight: 44,
  px: 3,
  whiteSpace: 'nowrap',
} as const
