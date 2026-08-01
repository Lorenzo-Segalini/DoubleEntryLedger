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

  if (!(error instanceof ApiError)) {
    return (
      <div role="alert" className="rounded border border-rose-300 bg-rose-50 p-3 text-sm dark:border-rose-800 dark:bg-rose-950/40">
        <p className="font-medium text-rose-900 dark:text-rose-200">Something went wrong</p>
        <p className="text-rose-800 dark:text-rose-300">{String((error as Error)?.message ?? error)}</p>
      </div>
    )
  }

  const { problem } = error
  const details = Object.entries(problem.details ?? {})

  return (
    <div role="alert" className="rounded border border-rose-300 bg-rose-50 p-3 text-sm dark:border-rose-800 dark:bg-rose-950/40">
      <p className="font-medium text-rose-900 dark:text-rose-200">{problem.title}</p>
      <p className="text-rose-800 dark:text-rose-300">{problem.detail}</p>

      {details.length > 0 && (
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs text-rose-800 dark:text-rose-300">
          {details.map(([key, value]) => (
            <div key={key} className="contents">
              <dt className="font-mono">{key}</dt>
              <dd className="tabular-nums">{String(value)}</dd>
            </div>
          ))}
        </dl>
      )}

      {problem.requestId && (
        <p className="mt-2 text-xs text-rose-700 dark:text-rose-400">
          Request <code className="font-mono">{problem.requestId}</code>
        </p>
      )}
    </div>
  )
}
