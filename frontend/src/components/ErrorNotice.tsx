import { ApiError } from '@/lib/problem'

/**
 * Shows what went wrong and what to do about it.
 *
 * The server sends a stable `code`, a human `detail`, a `details` map with the
 * values needed to fix the request, and a `requestId`. Rendering only the message
 * would discard the three most useful parts — in particular the requestId, which
 * is the one token a user can quote that finds the call in the server logs.
 */
export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null

  // Colour is not the signal here: the border, the heading and the `alert` role
  // all say "this failed" independently of anyone seeing the red.
  const shell = 'rounded border border-bad bg-bad-bg p-3 text-sm text-bad-bg-fg'

  if (!(error instanceof ApiError)) {
    return (
      <div role="alert" className={shell}>
        <p className="font-semibold">Something went wrong</p>
        <p>{String((error as Error)?.message ?? error)}</p>
      </div>
    )
  }

  const { problem } = error
  const details = Object.entries(problem.details ?? {})

  return (
    <div role="alert" className={shell}>
      <p className="font-semibold">{problem.title}</p>
      <p>{problem.detail}</p>

      {details.length > 0 && (
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
          {details.map(([key, value]) => (
            <div key={key} className="contents">
              <dt className="font-mono">{key}</dt>
              <dd className="tabular-nums">{String(value)}</dd>
            </div>
          ))}
        </dl>
      )}

      {problem.requestId && (
        <p className="mt-2 text-xs">
          Request <code className="font-mono">{problem.requestId}</code>
        </p>
      )}
    </div>
  )
}
