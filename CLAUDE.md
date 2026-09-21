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
- All threads are created through the single internal `ThreadFactory`.
- All test classes are named `*Test`; there is no `*IT` suffix. A test
  that needs Docker or a broker is annotated with `@IntegrationTest`
  (JUnit tag `integration`): surefire excludes the tag, failsafe runs
  only the tagged tests, and `-DskipITs` skips them.
- The prototype lives on the frozen `initial-setup` branch. It is a
  read-only reference (`git show initial-setup:<path>`) and never lands
  on `main`.

## Code style

The build enforces the layout: `./mvnw verify` fails on unformatted
code, and `./mvnw spotless:apply` fixes it. Run it before every commit.
Spotless applies the Eclipse JDT profile `config/eclipse-formatter.xml`
to the main and test sources of every module.

Enforced and fixed by `spotless:apply`:

- 4 spaces, 120 columns. The formatter keeps the line breaks of the
  author and only wraps what is too long.
- An empty body is `{}` on one line: `private Assignors() {}`,
  `g -> {}`.
- When a declaration (method, constructor, record) or the condition of
  an `if`, `while`, `for`, `switch`, `try` or `catch` spans several
  lines, the closing parenthesis goes on its own line: `) {`.
- Imports: static imports, then `java` and `javax`, then the rest, each
  group sorted and separated by a blank line. Unused imports go away.
- Comments and Javadoc are left as written.

Enforced by `config/CheckParentheses.java` in `verify`, fixed by hand:

- When the arguments of a call or an annotation span several lines, the
  closing parenthesis goes on its own line, aligned with the start of
  the statement: `);`, `),`, and `));` when the last argument is itself
  such a call. The check fails on a line that closes more parentheses
  than it opens and does not start with `)` or `}`. The formatter does
  not move these parentheses, so wrap a call that is too long by hand
  before it does it.
- The rule applies to every parenthesis, so it also reports a wrapped
  parenthesized expression such as `(a` / `+ b) * c`. Its limits are
  accepted: a line that closes as many parentheses as it opens is never
  reported, and the check runs from the root only, so a build with
  `-pl` or from a module directory skips it; CI covers it.
- The check runs before Spotless and also reports lines that the
  formatter repairs, such as `int second) {`. Run
  `./mvnw spotless:apply` first, then fix what is still reported.
- The check tests itself in `verify` on
  `config/check-parentheses-fixture.txt`. Add a case there when the
  check changes.

Guidance, not checked:

- In a wrapped call, put the arguments on one line, or one per line, or
  in meaningful groups such as the key and value pairs of `Map.of`.
- A lambda that does not fit behind its arrow moves to its own line
  whole (`value -> body`): the formatter never breaks after `->` unless
  the body is a block.
- Prefer `var` for local variables, enhanced-`for` variables included,
  when the type is evident from the right-hand side or irrelevant to
  the reader. With a diamond, move the type arguments to the right:
  `var ids = new HashSet<String>()`.
- Prefer modern Java 21 idioms when they make the code clearer: records,
  sealed types with pattern `switch`, pattern matching for `instanceof`,
  switch expressions, `List.of`, `Stream.toList()`, text blocks. Do not
  force them where they hurt readability.
- OpenRewrite can propose the `var` changes on demand. It is not part of
  the build, and its output must be reviewed: it also rewrites loop
  counters and can produce code that does not compile, such as `var`
  for a lambda. Run the `dryRun` goal to get a patch in
  `target/rewrite/`, or `run` to change the sources:

  ```sh
  ./mvnw org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
      -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:3.42.1 \
      -Drewrite.activeRecipes=org.openrewrite.java.migrate.lang.UseVar
  ```

## Module map

| Module | Role |
|---|---|
| `kaos-core` | Embeddable library with the fluent Java API (`Simulator`, topics, producers, consumer groups, faults, snapshots) |
| `kaos-server` | Javalin adapter exposing the HTTP API v1 (REST + SSE, OpenAPI); builds the shaded runnable jar |
| `kaos-ui` | React + TypeScript UI, built by Vite and embedded in the jar under `/ui` |

`kaos-core` and `kaos-server` exist as skeletons and `kaos-ui` is
planned; see `docs/ARCHITECTURE.md` for their status.

## Build and run

```sh
./mvnw verify                 # unit tests, Testcontainers ITs (needs Docker) and format checks; later also UI build + Vitest
./mvnw spotless:apply         # format the Java sources; verify fails on unformatted code
./mvnw verify -DskipITs       # skip the integration tests, keep the unit tests
./mvnw verify -DskipUi        # skip the frontend build (planned, no effect yet)
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
npm run dev                                             # in kaos-ui/ (planned); Vite proxies /api
```

A local broker for manual testing:

```sh
docker run -p 9092:9092 apache/kafka:4.3.1
```
