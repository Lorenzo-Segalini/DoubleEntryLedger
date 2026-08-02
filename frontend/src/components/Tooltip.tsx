import { useId, useState, type ReactNode } from 'react'

/**
 * An explanation attached to a term, on a focusable ⓘ button.
 *
 * A back office for double-entry bookkeeping is full of words that mean
 * something precise and something else in ordinary speech — a *credit* is not
 * money arriving, a *break* is not a defect, an *effective date* is not when
 * the row was written. Rather than dilute the labels into prose, the precise
 * word stays and the definition sits one keystroke away.
 *
 * Deliberately not the `title` attribute: that is unreachable by keyboard,
 * invisible on touch, unstyleable, and announced inconsistently by screen
 * readers. This is the WAI-ARIA tooltip pattern instead, and it satisfies
 * WCAG 2.2 §1.4.13 (Content on Hover or Focus) on all three counts:
 *
 * - **dismissible** — Escape hides it without moving focus;
 * - **hoverable** — the pointer handlers live on the wrapper, so the pointer
 *   can travel from the button onto the tooltip without it vanishing;
 * - **persistent** — it stays until blur, pointer-out or Escape, never on a
 *   timer.
 *
 * `aria-describedby` is attached only while the tooltip is rendered. A
 * permanent reference to an element that is not in the DOM is a dangling IDREF,
 * which axe flags and some screen readers drop silently.
 */
export function InfoTip({
  term,
  align = 'start',
  children,
}: {
  /** The thing being explained. Becomes the button's accessible name. */
  term: string
  /** Which edge to hang the bubble from, for terms near the right of a row. */
  align?: 'start' | 'end'
  children: ReactNode
}) {
  const id = useId()
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const [dismissed, setDismissed] = useState(false)

  const open = (hovered || focused) && !dismissed

  return (
    <span
      className="relative inline-flex align-middle"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => {
        setHovered(false)
        setDismissed(false)
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          // Stops here: Escape inside a dialog or a filter should not also be
          // read as a request to close whatever contains this tooltip.
          event.stopPropagation()
          setDismissed(true)
        }
      }}
    >
      <button
        type="button"
        aria-label={`Explain ${term}`}
        {...(open ? { 'aria-describedby': id } : {})}
        onFocus={() => setFocused(true)}
        onBlur={() => {
          setFocused(false)
          setDismissed(false)
        }}
        /*
          24px of hit target, 16px of layout.

          WCAG 2.2 §2.5.8 wants a 24×24 target, but a 24px-tall button beside a
          16px label makes that row taller than a row without one — so the
          control underneath it sits lower than its neighbours in the same grid
          row, and a column header with a tooltip rides above the ones without.
          The negative block margin lets the button keep its real 24px box for
          pointer and assistive-technology purposes while contributing only the
          text's own line height to the layout around it.
        */
        className="-my-1 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-muted transition hover:bg-surface-2 hover:text-fg"
      >
        <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false" className="h-3.5 w-3.5">
          <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.5" />
          <path d="M8 7v4.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          <circle cx="8" cy="4.6" r="0.9" fill="currentColor" />
        </svg>
      </button>

      {open && (
        <span
          role="tooltip"
          id={id}
          className={`absolute top-full z-30 mt-1 w-64 rounded border border-line-strong bg-surface-2 p-2 text-left text-xs font-normal normal-case tracking-normal text-fg shadow-lg ${
            align === 'end' ? 'right-0' : 'left-0'
          }`}
        >
          {children}
        </span>
      )}
    </span>
  )
}

/**
 * A term with its definition attached, for use inside a table header cell.
 *
 * The `<th>` it goes in **must** carry an explicit `aria-label` of the plain
 * term. A column header names every data cell beneath it, so without that the
 * whole column reads as "Debit Explain the debit column, 125.00 EUR" — the
 * tooltip's own label repeated down the table. The same reasoning is why
 * `Card` takes `tip` as a prop beside the heading rather than inside it, and
 * why headings elsewhere put `InfoTip` next to the <h1>, not within it.
 */
export function TipTerm({
  term,
  align,
  tip,
  children,
}: {
  term: string
  align?: 'start' | 'end'
  tip: ReactNode
  children: ReactNode
}) {
  return (
    <span className="inline-flex items-center gap-0.5">
      {children}
      <InfoTip term={term} {...(align ? { align } : {})}>
        {tip}
      </InfoTip>
    </span>
  )
}
