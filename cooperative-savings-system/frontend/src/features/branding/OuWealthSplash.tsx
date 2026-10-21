import './ouWealthSplash.css'

interface OuWealthSplashProps {
  ready: boolean
  exiting: boolean
  continueLabel: string
  onContinue: () => void
}

export function OuWealthSplash({
  ready,
  exiting,
  continueLabel,
  onContinue,
}: OuWealthSplashProps) {
  return (
    <div
      className={`ou-splash${ready ? ' is-ready' : ''}${exiting ? ' is-exiting' : ''}`}
      data-testid="ouwealth-splash"
      data-ready={ready ? 'true' : 'false'}
      role="presentation"
      onClick={() => {
        if (ready) onContinue()
      }}
    >
      <div className="ou-splash-fit">
        <div className="ou-splash-glow-layer">
          <div className="ou-splash-glow" data-anim="1" />
        </div>

        <div className="ou-splash-stack">
          <div className="ou-splash-mark-wrap" data-anim="1">
            <svg
              className="ou-splash-mark"
              width="230"
              height="230"
              viewBox="0 0 100 100"
              fill="none"
              aria-hidden="true"
            >
              <path
                className="ou-splash-ring"
                data-anim="1"
                pathLength={100}
                d="M80.7 21.4 A42 42 0 1 1 58.7 8.9"
                stroke="#F0862B"
                strokeWidth="8.5"
                strokeLinecap="round"
                strokeDasharray="100"
              />
              <path
                className="ou-splash-u-1"
                data-anim="1"
                pathLength={100}
                d="M24.5 32 V50 A25.5 25.5 0 0 0 75.5 50 V4"
                stroke="#FAF7F3"
                strokeWidth="1.55"
                strokeLinecap="round"
                strokeDasharray="100"
              />
              <path
                className="ou-splash-u-2"
                data-anim="1"
                pathLength={100}
                d="M26.5 32 V50 A23.5 23.5 0 0 0 73.5 50 V4"
                stroke="#DCD6CE"
                strokeWidth="1.55"
                strokeLinecap="round"
                strokeDasharray="100"
              />
              <path
                className="ou-splash-u-3"
                data-anim="1"
                pathLength={100}
                d="M28.5 32 V50 A21.5 21.5 0 0 0 71.5 50 V4"
                stroke="#BAB3AA"
                strokeWidth="1.55"
                strokeLinecap="round"
                strokeDasharray="100"
              />
              <path
                className="ou-splash-u-4"
                data-anim="1"
                pathLength={100}
                d="M30.5 32 V50 A19.5 19.5 0 0 0 69.5 50 V4"
                stroke="#989188"
                strokeWidth="1.55"
                strokeLinecap="round"
                strokeDasharray="100"
              />
              <path
                className="ou-splash-u-5"
                data-anim="1"
                pathLength={100}
                d="M32.5 32 V50 A17.5 17.5 0 0 0 67.5 50 V4"
                stroke="#787168"
                strokeWidth="1.55"
                strokeLinecap="round"
                strokeDasharray="100"
              />
              <path
                className="ou-splash-u-6"
                data-anim="1"
                pathLength={100}
                d="M34.5 32 V50 A15.5 15.5 0 0 0 65.5 50 V4"
                stroke="#59524A"
                strokeWidth="1.55"
                strokeLinecap="round"
                strokeDasharray="100"
              />
            </svg>
          </div>

          <div className="ou-splash-copy">
            <span className="ou-splash-wealth" data-anim="1">
              Wealth
            </span>
            <div className="ou-splash-rule" data-anim="1" />
            <span className="ou-splash-community" data-anim="1">
              COMMUNITY
            </span>
            <span className="ou-splash-tagline" data-anim="1">
              Accumulate your wealth in an <span className="ou-splash-instant">instant</span>
            </span>
          </div>
        </div>
      </div>

      <p className="ou-splash-continue" aria-live="polite">
        {ready ? continueLabel : ''}
      </p>
    </div>
  )
}
