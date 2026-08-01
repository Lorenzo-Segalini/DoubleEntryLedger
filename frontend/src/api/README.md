# api

`schema.d.ts` is **generated**, never edited by hand:

```bash
pnpm api:types    # requires the backend running on :8080
```

It is the OpenAPI contract the backend publishes at `/v3/api-docs`. Types come
from the contract rather than from hand-copied interfaces, so a backend change
that breaks the frontend fails `pnpm typecheck` in CI instead of at runtime.
See [docs/03-api.md §3.8](../../../docs/03-api.md#38-versioning-and-compatibility).
