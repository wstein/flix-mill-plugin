# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Use the checked-in bootstrap script. It downloads the Mill named in
`.mill-version` and provisions the JDK `build.mill` asks for, so no local Mill
or JDK install is assumed.

```bash
./mill flixMillPlugin.reformat                      # apply scalafmt
./mill flixMillPlugin.checkFormat + flixMillPlugin.compile + flixMillPlugin.test
./mill flixMillPlugin.test.testOnly flixmill.FlixTaskTests          # one suite
./mill flixMillPlugin.test 'flixmill.FlixTaskTests.runs Flix in the module directory by default'
./mill flixMillPlugin.publishLocal                  # -> ~/.ivy2/local
```

The two suites that need a real compiler take a `FLIX_JAR` pointing at a
downloaded `flix.jar`:

```bash
FLIX_JAR=/abs/path/flix.jar ./mill flixMillPlugin.test          # + real-compiler suite
FLIX_JAR=/abs/path/flix.jar ./mill flixMillPlugin.integration   # release gate
```

Run `actionlint` after touching `.github/workflows/`, and after touching
Renovate config:

```bash
npx --package renovate@44.5.3 -- renovate-config-validator .github/renovate.json5
```

Pin that version — `npx` may serve a stale cached Renovate, which wrongly
rejects `managerFilePatterns`.

## Architecture

A Mill plugin that shells out to the official Flix CLI. `FlixModule` is the
whole public surface; `FlixCommand` builds and runs `java -jar flix.jar <sub>`,
and `FlixArtifact` handles Flix-owned output paths.

The design deliberately does *not* translate Flix into a Mill-native build
model: no `compile` target, no `CompilationResult`, no downloading the compiler.
`docs/architecture-decision.md` is the decision log, including reversals; read
it before changing any of the invariants below, and record new decisions there
in the same weighted-reviewer format.

### Invariants that tests pin

Each of these was a real bug. Breaking one usually still compiles.

- **`flixWorkingDirectory` is a plain `def`, not a task.** Mill's source tasks
  are graph leaves and cannot depend on a task. Every tracked and generated
  path — `flixJar`, `flixManifest`, `flixSourceDirectories`, `build/class`,
  `artifact/*.fpkg` — derives from it, so one override relocates the whole
  project. Anything that resolves against `moduleDir` instead reintroduces a
  build that watches files nobody compiles.
- **`FlixArtifact.outputPathRef` suspends `FilesystemCheckerEnabled` and returns
  `withRevalidateOnce`.** Flix writes outside `Task.dest`, which Mill's checker
  forbids a task from reading; the suspension exists only to sign output the
  task just produced. Mill's default `Revalidate.Never` would silently restore
  the bug where deleting `build/` replayed a cached path to a missing file.
- **`flixJavaExecutable` is `Task.Input`, not a cached task.** Caching it
  records a JDK path in `out/` that survives a JDK upgrade and breaks every
  Flix task until `mill clean`.
- **The `.fpkg` name comes from the working directory's last segment**, not from
  `[package].name` in `flix.toml`. This is an observed contract of the Flix
  release pinned in CI, not a documented guarantee; re-verify it whenever that
  pin moves.

`check`, `build`, and `buildPkg` are cached tasks; `test`, `run`, `init`, and
`flixHelp` are `Task.Command` because they perform user-directed actions.
Generated `build/` and `artifact/` are deliberately not inputs.

### Test layers

- `flixMillPlugin/test` — `FlixModuleTests` (command assembly,
  output-signature policy, checker suspension), `FlixTaskTests` (task types and
  the working-directory contract via `mill-testkit`'s `UnitTester`), and
  `FlixCliIntegrationTests` (real compiler; skips without `FLIX_JAR`).
- `flixMillPlugin/integration` — the release gate. Publishes to a throwaway
  `publishLocalTestRepo` reached through `MILL_USER_TEST_REPO`, then drives a
  real Mill subprocess against the fixture under
  `flixMillPlugin/integration/resources/consumer-project`.
  It catches wrong coordinates, POM metadata, and meta-build imports that no
  unit test can see. It **fails** rather than skips when `FLIX_JAR` is unset —
  keep it that way; a gate that abstains reports success having built nothing.
  The fixture pins `0.0.0-PLACEHOLDER`, substituted per run, so it can never
  resolve a leftover build.

### Version pins

`build.mill`'s `millVersion = "1.0.6"` is the *oldest* Mill 1.x the published
artifact supports (with `platformSuffix = "_mill1"`), and is not the same thing
as `.mill-version`, which is what this repo builds with. Never "update" it as if
it were a dependency — raising it drops consumers.

`publishVersion` is a literal, and `.github/workflows/release.yml` refuses a tag
that disagrees with it. Bump the literal and tag the same version, or the
release fails before uploading — which is the point, because **the published
GitHub Pages Maven mirror is immutable by policy** (`Refuse to Republish an
Existing Version` in the release workflow): once a version is up, it does not
move under the same coordinate. The namespace is `io.github.wstein`;
`com.github.*` was tried first and abandoned — see the ADR. Never weaken the
tag check or the republish guard without being asked: together they are the
last chance to inspect a release that cannot be taken back.

The plugin publishes only to GitHub Pages, not Maven Central — `build.mill`
uses plain `PublishModule`, not `SonatypeCentralPublishModule`. Do not
reintroduce Central publishing without being asked.

Renovate (`.github/renovate.json5`) keeps the rest current, grouping every file
that states a version under one dependency so upgrades are never half-applied:
Mill spans `.mill-version` + `mill` + `mill.bat`; Flix spans the CI env var and
two places in `README.md`. Editing one by hand means editing all of them. A Mill
release that changes the bootstrap scripts themselves needs
`./mill updateMillScripts <version>`.

## Conventions

- Conventional commits with a scope matching the change (`fix(flix)`,
  `test(integration)`, `docs(adr)`, `ci`).
- Markdown wraps at 80 columns; Scala at 100 (`.scalafmt.conf`).
- `-Werror -Wunused:all` — an unused import fails the build.
- Comments explain *why*, especially the Mill-behavior reasons above; match that
  density rather than narrating what the code does.
- American spelling.
