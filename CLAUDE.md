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

`kaos-core` and `kaos-server` are built and `kaos-ui` is a scaffold;
see `docs/ARCHITECTURE.md` for their status.

## Build and run

```sh
./mvnw verify                 # unit tests, Testcontainers ITs (needs Docker), the format check, UI build + Vitest
./mvnw spotless:apply         # format the Java sources, add the UI license headers; verify fails otherwise
./mvnw verify -DskipITs       # skip the integration tests, keep the unit tests
./mvnw verify -DskipUi        # skip the frontend build (Node, npm, type check, Vite, Vitest)
./run.sh [options]            # build and run; all options go to the app, see ./run.sh --help
java -jar kaos-server/target/kaos-*.jar [--bootstrap-server localhost:9092] [--port 8081]
```

The runnable jar is the shaded `kaos-server/target/kaos-server-<version>.jar`.

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
