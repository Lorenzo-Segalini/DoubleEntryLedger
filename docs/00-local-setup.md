# 0. Local Setup

Everything needed to build, test and run the stack on a development machine.

## 0.0 Supported platforms

**macOS, Linux and Windows are all supported**, and nothing in the application is
tied to one of them. The runtime is a JVM, a browser bundle and PostgreSQL in
Docker: the same image that runs locally runs in CI and in production, and that
image is Linux (`eclipse-temurin:21-jre-alpine`) regardless of what you develop on.

| | macOS | Linux | Windows |
|---|---|---|---|
| Backend, frontend, database | ✅ | ✅ | ✅ |
| `docker compose` stack | ✅ | ✅ | ✅ via Docker Desktop + WSL2 |
| `pnpm` scripts | ✅ | ✅ | ✅ (`backend:*` go through `scripts/mvnw.mjs`) |
| Maven wrapper | `mvnw` | `mvnw` | `mvnw.cmd` |
| Testcontainers | ✅ | ✅ | ✅ WSL2 backend |

Three things in the repository exist specifically to keep this true, and are
worth knowing about before changing them:

- **`.gitattributes` forces LF on `mvnw`.** Git for Windows defaults to
  `core.autocrlf=true`; without this, checkout rewrites the wrapper with CRLF and
  it fails under WSL, Git Bash and Docker with `bad interpreter: /bin/sh^M`.
- **`scripts/mvnw.mjs` picks the right wrapper.** npm scripts run through
  `cmd.exe` on Windows, which cannot execute `./mvnw`.
- **No absolute JDK path is committed.** `.vscode/settings.json` deliberately
  contains none — see §0.4.

Windows users should work **inside WSL2** if given the choice. Docker Desktop's
WSL2 backend is where Testcontainers is best supported, and bind mounts across
the Windows/Linux filesystem boundary are slow enough to be noticeable in the
Vite dev server. Native Windows works; WSL2 works better.

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

## 0.2 Installing the toolchain

### macOS

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

Homebrew's `openjdk@21` is keg-only: it is not on `PATH`, and macOS does not know
it exists. Two separate steps follow from that, doing two different jobs.

**Required — point `JAVA_HOME` at it.** Without this the Maven wrapper falls back
to whatever `java` is on `PATH`, and the build fails with
`release version 21 not supported`:

```bash
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
```

Note the `libexec/…/Contents/Home` suffix. The keg prefix
(`/opt/homebrew/opt/openjdk@21`) has a working `bin/java` symlink, so Maven
accepts it, but it is not a real JDK home — no `release`, no `lib/modules` — and
IDE language servers fail on it in confusing ways (see §0.5).

**Optional — register it with macOS**, so `/usr/libexec/java_home -v 21` resolves
and JDK-aware tools (IntelliJ especially) offer it in their JDK list without
being told a path:

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
             /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

This is a symlink, not an install: removing it later (`sudo rm` on that path)
leaves the Homebrew JDK untouched.

> **Do not use `$(/usr/libexec/java_home -v 21)` before creating that symlink.**
> When no matching JDK is registered, it does not fail — it silently returns the
> newest one it *does* know about, which on a machine with only JDK 17 installed
> is JDK 17. `JAVA_HOME` then looks correctly set and the build still fails.

### Linux (Debian / Ubuntu)

```bash
# JDK 21 — Temurin, matching the production image
sudo apt-get install -y wget apt-transport-https gpg
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | gpg --dearmor | sudo tee /etc/apt/keyrings/adoptium.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb \
  $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo apt-get install -y temurin-21-jdk

echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64' >> ~/.bashrc

# Node via nvm, pinned by .nvmrc
nvm install && nvm use
corepack enable && corepack prepare pnpm@10.12.1 --activate

# Docker Engine + Compose plugin, then allow your user to reach the socket
sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker "$USER"   # log out and back in
```

On Fedora/RHEL the JDK is `sudo dnf install temurin-21-jdk`; on Arch,
`sudo pacman -S jdk21-openjdk`. `update-alternatives --config java` switches the
default if you have several.

The `usermod` step matters more than it looks: Testcontainers talks to
`/var/run/docker.sock` directly, and without group membership `./mvnw verify`
fails with a permission error rather than an obvious "Docker is not running".

### Windows

Work inside **WSL2** and follow the Linux instructions there. That is the
supported path and the one CI resembles.

```powershell
wsl --install -d Ubuntu
```

Then install Docker Desktop on Windows and enable *Settings → Resources → WSL
Integration* for that distribution, so `docker` works from inside WSL.

**Clone inside the WSL filesystem** (`~/projects/...`), not under `/mnt/c/...`.
Crossing the filesystem boundary makes Vite's file watching and Maven's I/O
several times slower, and inotify on `/mnt/c` is unreliable enough that hot
reload silently stops working. VS Code's *WSL* extension opens that directory as
a normal workspace.

If you must develop on native Windows instead:

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install OpenJS.NodeJS.LTS
winget install Docker.DockerDesktop
corepack enable
```

`JAVA_HOME` is set by the Temurin installer. Use `.\backend\mvnw.cmd` directly,
or `pnpm backend:test`, which selects the right wrapper for you. Keep the clone
on the Windows filesystem (`C:\…`), not on a UNC or network path — the Maven
wrapper cannot resolve those.

### Verify, on any platform

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
pnpm stack:db                    # PostgreSQL on :5432
pnpm backend:run           # Spring Boot on :8080, profile `local`
pnpm dev                   # Vite on :5173
```

**Everything in Docker** — matches CI and production:

```bash
pnpm stack:up                    # postgres + backend + frontend, waits for health
pnpm stack:down                  # stop
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

### No JDK path is committed

`.vscode/settings.json` deliberately contains no `java.configuration.runtimes`
entry. An absolute path is specific to one machine and one OS, and a committed
one is wrong for every contributor who is not on the machine it was written on.

The Java extension resolves its JDK from `JDK_HOME`, then `JAVA_HOME`, then
`PATH`, so setting `JAVA_HOME` (§0.2) configures the editor and the Maven wrapper
together, on every platform.

One caveat: an editor launched from a desktop icon or the Dock does not inherit
your shell environment, so `JAVA_HOME` will be invisible to it. Either launch it
with `code .` from a configured terminal, or put the path in your **user**
settings, where machine-specific values belong:

```jsonc
// macOS (Homebrew)      /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
// Linux (Temurin deb)   /usr/lib/jvm/temurin-21-jdk-amd64
// Windows (Temurin)     C:\\Program Files\\Eclipse Adoptium\\jdk-21...
"java.configuration.runtimes": [
  { "name": "JavaSE-21", "path": "<one of the above>", "default": true }
]
```

Getting this wrong is not a quiet failure. A path that is not a real JDK home —
one without `release` and `lib/modules` — makes the language server unable to
resolve `java.lang.Object`; it then writes Eclipse-JDT error class files into
`backend/target/`, which race Maven and make `./mvnw verify` fail from the
terminal with a compilation error that has nothing to do with your code.

VS Code extensions are recommended in `.vscode/extensions.json`; opening the
repository prompts to install them.

## 0.5 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `release version 21 not supported` / `invalid target release: 21` | `JAVA_HOME` unset, so Maven used the `java` on `PATH` | Set `JAVA_HOME` to the full `libexec/…/Contents/Home` path (§0.2) |
| `Could not find a valid Docker environment` | Daemon not running | `open -a Docker`, wait for it, retry |
| `mvnw: permission denied` | Lost executable bit | `chmod +x backend/mvnw` |
| `bad interpreter: /bin/sh^M` (Linux/WSL/Git Bash) | `mvnw` checked out with CRLF | `.gitattributes` prevents this; if the clone predates it, `git rm --cached -r . && git reset --hard` |
| `permission denied … /var/run/docker.sock` (Linux) | User not in the `docker` group | `sudo usermod -aG docker "$USER"`, then log out and back in |
| `'.' is not recognized` from `pnpm backend:test` (Windows) | An old script calling `./mvnw` through `cmd.exe` | Scripts now go through `scripts/mvnw.mjs`; pull latest |
| Hot reload stops working (WSL) | Repo lives under `/mnt/c/...` | Clone inside the WSL filesystem (§0.2) |
| `ERR_PNPM_NO_LOCKFILE` in CI | Lockfile not committed | Commit `pnpm-lock.yaml` |
| Tests unusually slow on Apple Silicon | x86_64 JDK under Rosetta | Check `file "$JAVA_HOME/bin/java"`, install an arm64 JDK |
| Port 5432 already in use | A host PostgreSQL is running | `POSTGRES_PORT=5433` in `infra/.env` |
| Spotless fails the build | Formatting drift | `cd backend && ./mvnw spotless:apply` |
| `pnpm up` updates dependencies instead of starting the stack | `up` is pnpm's own alias for `update`, and a built-in wins over a script of the same name | Use `pnpm stack:up`. The stack scripts are namespaced for exactly this reason |
| `Unresolved compilation problems: The type java.lang.Object cannot be resolved` from `./mvnw` | VS Code's Java language server is compiling into the same `target/` with a misconfigured JDK, and its broken class files race Maven's | Point `java.configuration.runtimes` at a **real** JDK home (one with `release` and `lib/modules`), not Homebrew's keg prefix, then reload the VS Code window |
| `401` on `/swagger-ui.html` | Expected while the skeleton has no `SecurityConfig` — Spring Security denies everything by default | Resolved when `dev.lseg.ledger.security` lands ([ADR-0007](adr/0007-jwt-authentication-and-rbac.md)) |

---

Next: [Domain Model](01-domain-model.md).
