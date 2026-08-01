# 0. Local Setup

Everything needed to build, test and run the stack on a development machine.

## 0.1 Required versions

| Tool | Version | Why this one |
|---|---|---|
| **JDK** | **21** (LTS) | `pom.xml` targets 21 and the production image is `eclipse-temurin:21`. Java 17 will not compile the project. |
| **Maven** | 3.9.9 | Supplied by the committed wrapper (`backend/mvnw`) — do **not** install it. The wrapper downloads and caches Maven itself. |
| **Node** | 22.x | Pinned in `.nvmrc`. Vite 8 and the toolchain need ≥ 20.19. |
| **pnpm** | 10.x | Declared in `packageManager`. npm and yarn are not supported here — the lockfile is pnpm's. |
| **Docker** | Engine 24+ with Compose v2 | Runs PostgreSQL locally, and **Testcontainers requires a live daemon**: `./mvnw verify` fails without it. |
| **PostgreSQL** | 17 | Not installed on the host — it comes from `infra/compose.yaml`. |
| **Git** | 2.40+ | |

### Why the JDK version is not negotiable

The build sets `<java.version>21</java.version>`. A JDK 17 toolchain fails at
`maven-compiler-plugin` with `invalid target release: 21`. On Apple Silicon,
also check you are on an **arm64** build — an x86_64 JDK runs under Rosetta and
roughly doubles test times:

```bash
file "$JAVA_HOME/bin/java"     # want: Mach-O 64-bit executable arm64
```

### Why there is no in-memory database option

Half the ledger's invariants live in PostgreSQL-specific features: deferred
constraint triggers, stored generated columns, partial unique indexes, `pg_trgm`.
A test against H2 would pass while verifying behaviour that production does not
have. Docker is therefore a hard requirement for `./mvnw verify`, and
`./mvnw test` (unit + property only) is the Docker-free subset.

## 0.2 macOS from scratch

```bash
# JDK 21 (arm64)
brew install openjdk@21

# Node via nvm, pinned by .nvmrc
nvm install && nvm use

# pnpm
corepack enable && corepack prepare pnpm@10.12.1 --activate

# Docker Desktop
brew install --cask docker && open -a Docker
```

Homebrew's `openjdk@21` is keg-only, so it is not on `PATH` by default. Either
export `JAVA_HOME` per shell:

```bash
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@21' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
```

…or register it with the system so `/usr/libexec/java_home -v 21` finds it and
tools like IntelliJ and the VS Code Java extension detect it automatically:

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
             /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

The symlink is recommended. Without it, every JDK-aware tool has to be pointed
at the Homebrew path by hand.

Verify:

```bash
java -version         # openjdk version "21.x"
node -v               # v22.x
pnpm -v               # 10.x
docker info           # must not error
```

## 0.3 First run

```bash
git clone https://github.com/LorenzoSegaliniAtex/DoubleEntryLedger.git
cd DoubleEntryLedger

cp infra/.env.example infra/.env
pnpm install
```

Three ways to run, in increasing order of realism:

**Database only, app from your IDE** — the fastest inner loop:

```bash
pnpm db                    # PostgreSQL on :5432
pnpm backend:run           # Spring Boot on :8080, profile `local`
pnpm dev                   # Vite on :5173
```

**Everything in Docker** — matches CI and production:

```bash
pnpm up                    # postgres + backend + frontend, waits for health
pnpm down                  # stop
```

**Tests:**

```bash
pnpm backend:test          # ./mvnw verify — needs Docker running
pnpm test                  # Vitest
pnpm --filter frontend test:e2e   # Playwright against the Compose stack
```

| | |
|---|---|
| Back office | http://localhost:5173 |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

## 0.4 Editor

Both work for Java. The trade-off is not the one people usually assume.

**VS Code** + *Extension Pack for Java* + *Spring Boot Extension Pack* (both
free) is genuinely capable: Eclipse JDT language server, debugging, JUnit
running, Maven integration, and — importantly — real Spring support, including
`application.yml` key completion, bean and endpoint navigation, and live
actuator data from a running app.

**IntelliJ IDEA Community** is stronger at core Java: refactoring across a
module, inspections, and a debugger that handles concurrent tests more
comfortably (relevant here — see the 32-thread idempotency test in
[`docs/07-testing.md §7.4`](07-testing.md#74-idempotency-under-concurrency)).
It has **no** Spring-specific features and no database tool; both are Ultimate.

**IntelliJ IDEA Ultimate** is the best of the three for this project, and since
2024 JetBrains offers a free non-commercial licence, which a portfolio project
qualifies for. It adds Spring inspections and a database tool that will
introspect the ledger schema and run the queries from
[`docs/02-data-model.md`](02-data-model.md) straight against the Compose Postgres.

If you already use VS Code, staying there for the whole monorepo is a perfectly
defensible choice and costs less than running two editors. The repository
supports either: `.editorconfig` normalises formatting, and Spotless
(`./mvnw spotless:apply`) is the authority on Java style regardless of editor,
so there is no formatting war between them.

VS Code extensions are recommended in `.vscode/extensions.json`; opening the
repository prompts to install them.

## 0.5 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `invalid target release: 21` | JDK 17 on `PATH` | `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `Could not find a valid Docker environment` | Daemon not running | `open -a Docker`, wait for it, retry |
| `mvnw: permission denied` | Lost executable bit | `chmod +x backend/mvnw` |
| `ERR_PNPM_NO_LOCKFILE` in CI | Lockfile not committed | Commit `pnpm-lock.yaml` |
| Tests unusually slow on Apple Silicon | x86_64 JDK under Rosetta | Check `file "$JAVA_HOME/bin/java"`, install an arm64 JDK |
| Port 5432 already in use | A host PostgreSQL is running | `POSTGRES_PORT=5433` in `infra/.env` |
| Spotless fails the build | Formatting drift | `cd backend && ./mvnw spotless:apply` |
| `401` on `/swagger-ui.html` | Expected while the skeleton has no `SecurityConfig` — Spring Security denies everything by default | Resolved when `dev.lseg.ledger.security` lands ([ADR-0007](adr/0007-jwt-authentication-and-rbac.md)) |

---

Next: [Domain Model](01-domain-model.md).
