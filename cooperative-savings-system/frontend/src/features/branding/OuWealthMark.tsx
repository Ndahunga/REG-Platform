interface OuWealthMarkProps {
  size?: number
}

/** Static orange/white OuWealth mark — same SVG geometry as the splash, no animation. */
export function OuWealthMark({ size = 40 }: OuWealthMarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      fill="none"
      aria-hidden="true"
      style={{ display: 'block', overflow: 'visible', flexShrink: 0 }}
    >
      <path
        d="M80.7 21.4 A42 42 0 1 1 58.7 8.9"
        stroke="#F0862B"
        strokeWidth="8.5"
        strokeLinecap="round"
      />
      <path
        d="M24.5 32 V50 A25.5 25.5 0 0 0 75.5 50 V4"
        stroke="#FAF7F3"
        strokeWidth="1.55"
        strokeLinecap="round"
      />
      <path
        d="M26.5 32 V50 A23.5 23.5 0 0 0 73.5 50 V4"
        stroke="#DCD6CE"
        strokeWidth="1.55"
        strokeLinecap="round"
      />
      <path
        d="M28.5 32 V50 A21.5 21.5 0 0 0 71.5 50 V4"
        stroke="#BAB3AA"
        strokeWidth="1.55"
        strokeLinecap="round"
      />
      <path
        d="M30.5 32 V50 A19.5 19.5 0 0 0 69.5 50 V4"
        stroke="#989188"
        strokeWidth="1.55"
        strokeLinecap="round"
      />
      <path
        d="M32.5 32 V50 A17.5 17.5 0 0 0 67.5 50 V4"
        stroke="#787168"
        strokeWidth="1.55"
        strokeLinecap="round"
      />
      <path
        d="M34.5 32 V50 A15.5 15.5 0 0 0 65.5 50 V4"
        stroke="#59524A"
        strokeWidth="1.55"
        strokeLinecap="round"
      />
    </svg>
  )
}
