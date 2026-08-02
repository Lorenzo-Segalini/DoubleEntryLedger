import { cloneElement, useId, type ReactElement, type ReactNode } from 'react'

export function Card({
  title,
  tip,
  actions,
  children,
}: {
  title?: ReactNode
  /**
   * An `<InfoTip>` for the card's subject. A separate prop rather than part of
   * `title` because it must sit *beside* the heading: a button inside an <h2>
   * becomes part of the heading's accessible name, and the card would announce
   * as "Out of balance Explain out of balance".
   */
  tip?: ReactNode
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="rounded-lg border border-line bg-surface shadow-sm">
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <div className="flex items-center gap-0.5">
            {title && <h2 className="text-sm font-semibold text-fg">{title}</h2>}
            {tip}
          </div>
          {actions}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

export type Tone = 'neutral' | 'good' | 'warn' | 'bad'

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  // Each pair is a background and the foreground measured against it, not
  // against the card. Colour is never the only signal: every badge carries the
  // word too, so a red/green distinction is not load-bearing.
  const tones: Record<Tone, string> = {
    neutral: 'bg-surface-2 text-fg',
    good: 'bg-good-bg text-good-bg-fg',
    warn: 'bg-warn-bg text-warn-bg-fg',
    bad: 'bg-bad-bg text-bad-bg-fg',
  }
  return <span className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${tones[tone]}`}>{children}</span>
}

export function Button({
  children,
  variant = 'primary',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  /*
    The disabled state is a real colour pair, not the usual pale-grey-on-white.
    WCAG exempts disabled controls from contrast, but this UI disables the one
    button that matters — Post entry, until the difference reaches zero — and a
    control nobody can read is a control nobody can tell is nearly ready.
  */
  const disabled =
    'disabled:cursor-not-allowed disabled:border-line-strong disabled:bg-surface-2 disabled:text-muted'

  // Every variant carries a border, transparent where it is not wanted, so
  // enabling a button never shifts the row it sits in by a pixel.
  const base = 'rounded border border-transparent px-3 py-1.5 text-sm font-medium transition'

  const variants = {
    primary: 'bg-solid text-solid-fg hover:opacity-90',
    ghost: 'border-line-strong text-fg hover:bg-surface-2',
    danger: 'bg-bad text-surface hover:opacity-90',
  }

  return (
    <button
      {...props}
      className={`${base} ${variants[variant]} ${disabled} ${props.className ?? ''}`}
    >
      {children}
    </button>
  )
}

/**
 * A labelled control.
 *
 * The label is a sibling with `htmlFor` rather than a wrapper, so that a
 * tooltip button can sit beside the label text without becoming part of the
 * control's accessible name — wrapping would make the field announce as
 * "Effective date Explain effective date".
 *
 * Hint and error text are wired to the control through `aria-describedby`, so a
 * screen reader reads them as part of the field rather than as loose text
 * somewhere after it, and `aria-invalid` marks the control the server rejected.
 */
export function Field({
  label,
  tip,
  hint,
  error,
  children,
}: {
  label: string
  /** Explanation for a term whose everyday meaning is not the one intended. */
  tip?: ReactNode
  // `| undefined` because callers pass a lookup that may miss, and
  // exactOptionalPropertyTypes treats that as different from omitting the prop.
  hint?: string | undefined
  error?: string | undefined
  children: ReactElement<{
    id?: string
    'aria-describedby'?: string
    'aria-invalid'?: boolean
  }>
}) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`

  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')

  const injected: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean } = { id }
  if (describedBy) injected['aria-describedby'] = describedBy
  if (error) injected['aria-invalid'] = true

  return (
    <div className="block">
      <div className="mb-1 flex items-center gap-0.5">
        <label htmlFor={id} className="block text-xs font-medium text-fg">
          {label}
        </label>
        {tip}
      </div>

      {cloneElement(children, injected)}

      {hint && (
        <span id={hintId} className="mt-1 block text-xs text-muted">
          {hint}
        </span>
      )}
      {error && (
        <span id={errorId} role="alert" className="mt-1 block text-xs font-medium text-bad">
          {error}
        </span>
      )}
    </div>
  )
}

/*
  A single control style, so a <select> and an <input type="date"> cannot drift
  apart. `bg-surface`/`text-fg` are stated rather than inherited: a control that
  inherits nothing renders with the browser's own white background and black
  text, which is exactly the combination that disappears on a dark page.
*/
export const inputClass =
  'w-full rounded border border-line-strong bg-surface px-2 py-1.5 text-sm text-fg'

/** Column header cells. `scope` is what lets a screen reader read a cell's column. */
export const thClass = 'pb-2 text-left align-bottom font-medium'

export const theadRowClass = 'border-b border-line text-left text-xs uppercase tracking-wide text-muted'

export const tbodyRowClass = 'border-b border-line'

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-8 text-center text-sm text-muted">{children}</p>
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <p role="status" className="py-8 text-center text-sm text-muted">
      {label}…
    </p>
  )
}
