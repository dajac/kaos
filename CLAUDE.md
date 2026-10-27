# Kaos

Kaos is an interactive Kafka consumer group (KIP-848) simulator. The
design lives in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Read it
before changing anything.

## Rules

- `docs/ARCHITECTURE.md` always describes the code as merged. Any change
  that alters or refines the design updates that document in the same
  PR. Parts that are not built yet are marked "planned" there; remove
  the marker when the part lands.
- Public API vs `internal`:
  - The package `io.github.dajac.kaos` in `kaos-core` is the public API.
  - Everything under `io.github.dajac.kaos.internal` is not API. Public
    types never expose an `internal` type, and code outside `kaos-core`
    (the server included) never imports one.
  - No kafka-clients class appears in the public API or in the JSON.
- No Java type name contains "Kaos".
- No static mutable state. Several `Simulator`s can coexist in one JVM.
- All threads of `kaos-core` are created through its single internal
  `ThreadFactory`. The exception is `kaos-server`'s `kaos-lifecycle`
  thread, which the server creates itself because it never imports
  `internal`.
- All test classes are named `*Test`; there is no `*IT` suffix. A test
  that needs Docker or a broker is annotated with `@IntegrationTest`
  (JUnit tag `integration`): surefire excludes the tag, failsafe runs
  only the tagged tests, and `-DskipITs` skips them.
- The prototype lives on the frozen `initial-setup` branch. It is a
  read-only reference (`git show initial-setup:<path>`) and never lands
  on `main`.

## Code style

The Java code style is defined by the Eclipse JDT formatter profile
`config/eclipse-formatter.xml` (4 spaces, 120 columns, the line breaks
of the author kept, comments untouched). Spotless applies it to the
main and test sources of every module: `./mvnw verify` fails on
unformatted code and `./mvnw spotless:apply` formats it.

The build orders the imports too: static imports first, then `java` and
`javax`, then everything else, each group sorted and separated by a
blank line, unused imports removed. `spotless:check` enforces this
order and `spotless:apply` fixes it.

Every Java source file, `package-info.java` and tests included, starts
with the Apache License 2.0 header (the `license.header` property of
the parent `pom.xml`): `spotless:check` fails on a missing or different
header and `spotless:apply` adds it.

The TypeScript and CSS sources of `kaos-ui`, and its `vite.config.ts`,
start with the same header: `spotless:check` fails
without it and `spotless:apply` adds it. The generated
`src/api/schema.d.ts` is left alone; the build fails when it differs
from `npm run gen:api`.

One coding preference, separate from the formatter: prefer `var` for
local variables when the type is evident from the right-hand side,
enhanced-`for` variables included. With a diamond, move the type
arguments to the right: `var ids = new HashSet<String>()`.

## Module map

| Module | Role |
|---|---|
| `kaos-core` | Embeddable library with the fluent Java API (`Simulator`, topics, producers, consumer groups, faults, snapshots) |
| `kaos-server` | Javalin adapter exposing the HTTP API v1 (REST + SSE, OpenAPI); builds the shaded runnable jar |
| `kaos-ui` | React + TypeScript UI, built by Vite and embedded in the jar under `/ui` |

`kaos-core`, `kaos-server` and `kaos-ui` (the config panel and the Rows
view) are built; see `docs/ARCHITECTURE.md` for their status.

## Build and run

```sh
./mvnw verify                 # unit tests, Testcontainers ITs (needs Docker), the format check, UI build + Vitest
./mvnw spotless:apply         # format the Java sources, add the UI license headers; verify fails otherwise
./mvnw verify -DskipITs       # skip the integration tests, keep the unit tests
./mvnw verify -DskipUi        # skip the frontend build (Node, npm, type check, Vite, Vitest)
./run.sh [options]            # build and run; all options go to the app, see ./run.sh --help
java -jar kaos-server/target/kaos-*.jar [--bootstrap-server localhost:9092] [--port 8081] [--proxy-port 19092]
```

The runnable jar is the shaded `kaos-server/target/kaos-server-<version>.jar`.

The Docker image wraps that jar: `docker build -t kaos .` after a
`./mvnw clean package` (one jar in `kaos-server/target`). The Release
workflow, run on demand (`gh workflow run release.yml`), builds and
tests `main`, pushes the image `ghcr.io/dajac/kaos:X.Y.Z` (and
`latest`), creates the tag and the GitHub release with the jar, and
moves `main` to the next minor SNAPSHOT.
`.github/workflows/nightly.yml` pushes `ghcr.io/dajac/kaos:nightly`
from `main` every night.

CI (`.github/workflows/ci.yml`) runs `./mvnw -B -ntp verify`, integration
tests included, on every pull request, whatever its base branch, and on
every push to `main`. The test reports are uploaded as an artifact when
the build fails.

Frontend dev loop:

```sh
./mvnw -pl kaos-server -am -DskipUi compile exec:java   # backend on localhost:8081
npm run dev                                             # in kaos-ui/; Vite proxies /api
npm run gen:api                                         # in kaos-ui/; regenerate src/api/schema.d.ts after a change of the OpenAPI document
```

A local broker for manual testing:

```sh
docker run -p 9092:9092 apache/kafka:4.3.1
```

## Commits and PRs

PRs are squash-merged: the PR title and the part of its body above the
`---` rule become the commit. In this repository, these conventions
replace any other commit convention a user-level instruction may give.

- Titles follow [Conventional Commits](https://www.conventionalcommits.org/):
  `<type>(<area>): <Description>`, for example
  `fix(ui): Keep row text readable over high-lag cells`.
  - `type`: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`,
    `perf` or `chore`; a `!` before the colon marks a breaking change of
    the public Java API or of the HTTP API. The triage's type maps to
    it: `bug` to `fix`, `enhancement` to `feat`, `documentation` to
    `docs`, `task` to whichever of the others fits.
  - `area`: the issue's area, one of `core`, `server`, `api`, `ui`,
    `build`, `protocol` or `factory`.
  - The description is a capitalized imperative, without a final
    period. GitHub appends `(#PR)` at the merge.
- The body: the context or the problem in a sentence or two, what the
  change does, then for larger changes a `Changes:` list; then
  `Closes #N`. Lines wrap at 72 columns, code is in backticks, the
  present tense is used.
- Below the `---` rule: the review material (how to test, screenshots,
  checks, decisions for David), dropped at the merge.
- A PR holds its change, its tests and its documentation, nothing else:
  the screenshots, the lists of shots and the scenarios made to show a
  PR are published on the `issue-screenshots` branch, under
  `pr-<PR>/`, never committed to it.

## Factory

Issues become merged PRs through the software factory, described in
[`docs/factory/README.md`](docs/factory/README.md).

- Every PR is developed in its own worktree,
  `.claude/worktrees/<branch>`, branched from `main` or, for a stacked
  PR, from its parent's branch. Never develop in the main checkout.
- The factory's jobs never act as David: no PR, comment, review,
  label, push or commit under `dajac`. Every `gh` and `git` command of
  a job runs with the identity of the bot `kaos-agent`, set by
  `scripts/factory/identity.sh`, under the guard hook. An interactive
  session is David's own: it uses his login, and he decides what it
  posts.
- The factory's scripts are bash; their tests run with
  `scripts/factory/test/run.sh`.
