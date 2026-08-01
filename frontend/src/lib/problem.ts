/**
 * RFC 9457 `application/problem+json`, parsed into something the UI can act on.
 *
 * The backend returns a stable `code` (the `LedgerError` enum) alongside the
 * human `detail`, plus a `details` map carrying the values needed to fix the
 * request — how much an entry is out by, which account code does not exist.
 * Rendering only `detail` would throw all of that away.
 */

export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  instance?: string
  /** The LedgerError enum name, e.g. UNBALANCED_ENTRY. Stable across wording changes. */
  code?: string
  requestId?: string
  details?: Record<string, unknown>
  errors?: Array<{ pointer: string; message: string }>
}

export class ApiError extends Error {
  readonly problem: ProblemDetail

  constructor(problem: ProblemDetail) {
    super(problem.detail || problem.title)
    this.name = 'ApiError'
    this.problem = problem
  }

  get status(): number {
    return this.problem.status
  }

  get code(): string | undefined {
    return this.problem.code
  }

  /**
   * The one token a user can quote when reporting a failure. It ties the call to
   * the server logs and, if it got as far as posting, to the journal row.
   */
  get requestId(): string | undefined {
    return this.problem.requestId
  }

  /** Field-level messages keyed by JSON pointer, for attaching to form inputs. */
  fieldErrors(): Record<string, string> {
    const result: Record<string, string> = {}
    for (const error of this.problem.errors ?? []) {
      result[error.pointer] = error.message
    }
    return result
  }

  is(code: string): boolean {
    return this.problem.code === code
  }
}

const FALLBACK_TITLES: Record<number, string> = {
  401: 'Not signed in',
  403: 'Not permitted',
  404: 'Not found',
  409: 'Conflict',
  422: 'Rejected',
  500: 'Server error',
}

export async function toApiError(response: Response): Promise<ApiError> {
  // A 500 from a proxy, a gateway timeout, an empty body — none of those are
  // problem+json, and the UI still needs something to show.
  let problem: ProblemDetail = {
    type: 'about:blank',
    title: FALLBACK_TITLES[response.status] ?? 'Request failed',
    status: response.status,
    detail: `The server responded with ${response.status}.`,
  }

  try {
    const body = (await response.json()) as Partial<ProblemDetail>
    if (body && typeof body === 'object' && typeof body.status === 'number') {
      problem = { ...problem, ...body } as ProblemDetail
    }
  } catch {
    // Body was not JSON. The fallback above already says what happened.
  }

  return new ApiError(problem)
}
