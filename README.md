# Flix Mill Plugin

[![CI](https://github.com/wstein/flix-mill-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/wstein/flix-mill-plugin/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Mill](https://img.shields.io/badge/Mill-1.x-brightgreen)](https://mill-build.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net)
[![Flix](https://img.shields.io/badge/Flix-0.75.1-blueviolet)](https://flix.dev)

Mill support for [Flix](https://flix.dev) projects. The plugin executes the
official `flix.jar` command-line interface from a Mill module.

## Status

The plugin is implemented, tested against Flix 0.75.1, and published for Mill
1.x as `io.github.wstein:flix-mill-plugin_mill1_3` to a
[GitHub Pages Maven repository](https://wstein.github.io/flix-mill-plugin/) --
not Maven Central. See [Use in a Mill build](#use-in-a-mill-build).

## Design

The project-local `flix.jar` is an explicit build input. This matches the
[Flix CLI setup](https://doc.flix.dev/getting-started.html): install Java 21+,
download `flix.jar` into the project directory, run `java -jar flix.jar init`,
then run `java -jar flix.jar run`.

The plugin exposes cached checking and packaging tasks, and command tasks for
executing tests, programs, and `init`. Each command runs in the module's Flix
working directory, so Flix finds that project's `flix.toml` manifest.

## Use in a Mill build

The plugin is not on Maven Central, so add its GitHub Pages Maven repository
alongside the usual dependency, import the trait, and extend it from the
module that is the root of the Flix project:

```scala
//| mvnDeps:
//| - io.github.wstein::flix-mill-plugin::0.1.1
//| repositories:
//| - https://wstein.github.io/flix-mill-plugin/maven/

import flixmill.FlixModule

object app extends FlixModule
```

For local development against an unreleased change, publish into the local
Ivy repository instead, which Mill's default resolvers already search (no
`repositories:` entry needed):

```text
./mill flixMillPlugin.publishLocal
```

Put `flix.jar`, `flix.toml`, `src/`, and (optionally) `test/` in `app/`. The
defaults may be overridden when a project manages its Java executable, compiler
JAR, or working directory differently:

```scala
object app extends FlixModule {
  override def flixJar = Task.Source(moduleDir / "tools" / "flix-0.75.1.jar")
}
```

`flixWorkingDirectory` is a plain `def`, not a task, because Mill's source tasks
cannot depend on one. Everything the plugin tracks or validates hangs off it, so
a single override relocates the compiler JAR, the manifest, the source roots,
and the generated output together:

```scala
object app extends FlixModule {
  override def flixWorkingDirectory = moduleDir / "flix"
}
```

### Tasks

| Mill task | Flix command |
| --- | --- |
| `app.check` | `check` |
| `app.build` | `build` |
| `app.test` | `test` |
| `app.run -- arg` | `run arg` |
| `app.buildPkg` | `build-pkg` |
| `app.init` | `init` |
| `app.flixHelp` | `--help` |

`check`, `build`, and `buildPkg` are cached tasks. `test`, `run`, `init`, and
`flixHelp` are command tasks because they perform user-directed actions. The
cached tasks track `flix.toml`, `src/`, `test/`, and the compiler JAR; edits to
generated `build/` and `artifact/` output do not invalidate them. Override
`flixSourceDirectories` when a project has additional source roots.

`build` and `buildPkg` return their validated Flix-owned output as a `PathRef`
under the Flix working directory; `buildPkg` returns
`artifact/<working-directory-name>.fpkg`. Neither is a Mill-owned `Task.dest`
output. Those `PathRef`s are revalidated when Mill reads a cached result back,
so removing the output re-runs the task rather than replaying a path to a file
that is no longer there.

The Flix compiler writes outside `Task.dest`, which Mill's filesystem checker
normally forbids a task from reading. The plugin suspends that check only to
compute the signature of output it has just produced itself.

## Development

The only requirement is a JVM. The checked-in `./mill` bootstrap script (or
`mill.bat` on Windows) downloads the Mill version named in `.mill-version` and
provisions the JDK that `build.mill` requests, so no separate Mill or JDK
installation is needed:

```text
./mill flixMillPlugin.reformat
./mill flixMillPlugin.checkFormat + flixMillPlugin.compile + flixMillPlugin.test
```

To include the real-compiler integration test, download a Flix JAR and run:

```text
FLIX_JAR=/absolute/path/to/flix.jar ./mill flixMillPlugin.test
```

Before releasing, run the consumer gate. It publishes the plugin to a throwaway
repository, resolves it from a separate Mill subprocess, and builds real Flix
projects against it, so it catches wrong coordinates, POM metadata, or
meta-build imports that no unit test can see. It requires `FLIX_JAR` and fails
if the variable is unset, because a gate that quietly passes is worse than none:

```text
FLIX_JAR=/absolute/path/to/flix.jar ./mill flixMillPlugin.integration
```

Both suites create their projects in temporary directories. Neither modifies the
repository, and the gate publishes to a Mill-owned directory rather than
`~/.ivy2/local`.

## Continuous integration

Every push and pull request runs
[the CI workflow](.github/workflows/ci.yml), which splits fast feedback from the
slow release gate:

- **Format, compile, unit tests** — runs the commands above without
  `FLIX_JAR`, covering the path a contributor takes with no compiler
  downloaded.
- **Real-compiler and consumer gate** — downloads a pinned Flix release, then
  runs the real-compiler suite and the consumer gate against it.

The Flix version is pinned rather than tracking `releases/latest`: the
`artifact/<project-directory>.fpkg` naming rule the plugin depends on is an
observed contract of that release, so an upgrade belongs in a reviewed pull
request that re-tests the rule instead of arriving unannounced on an unrelated
change.

## Dependency updates

[Renovate](.github/renovate.json5) opens weekly upgrade pull requests and tracks
progress on a dependency dashboard issue. Neither Renovate nor Dependabot has a
Mill manager, so the Mill-specific versions are matched by custom rules:

| Dependency | Files kept in step |
| --- | --- |
| Mill | `.mill-version`, `mill`, `mill.bat` |
| Flix | `.github/workflows/ci.yml`, `README.md` |
| Scala | `build.mill` |
| scalafmt | `.scalafmt.conf` |
| GitHub Actions | `.github/workflows/ci.yml` |

Each dependency covers every file that states its version, so an upgrade is
never half-applied. A Flix bump is graded by the consumer gate in its own pull
request, which is what re-tests the `.fpkg` naming rule.

Two versions are deliberately excluded. `millVersion` in `build.mill` is the
oldest Mill 1.x the published artifact supports, so raising it is a
compatibility decision, not an upgrade. `runs-on: ubuntu-latest` is intentional
and is not pinned.

A Mill release that changes the bootstrap scripts themselves, rather than only
the version they default to, still needs `./mill updateMillScripts <version>`.

## Publishing

The plugin is Apache-2.0 licensed and publishes as
`io.github.wstein:flix-mill-plugin_mill1_3`. The artifact uses the `_mill1`
platform suffix and compiles against Mill 1.0.6 for Mill 1.x compatibility,
while the build itself uses the current stable Mill release.

To publish locally, into a repository Mill's default resolvers search:

```text
./mill flixMillPlugin.publishLocal
```

### Releasing to GitHub Pages

Releases go out from a tag.
[The release workflow](.github/workflows/release.yml) refuses to run from a
branch, and refuses a tag that disagrees with `publishVersion`, because **the
published mirror is immutable** — a version that goes out wrong cannot be
withdrawn, only superseded.

```text
# 1. set publishVersion in build.mill, commit
# 2. tag and push
git tag v0.2.0 && git push origin v0.2.0
```

The workflow re-runs the consumer gate, then publishes standard Maven layout
(`publishM2Local`) plus checksums, an additive `maven-metadata.xml`, and a
generated landing page (all built by the unpublished `publishTools` module) to
the `gh-pages` branch, deployed via GitHub Pages. No account, secret, or
signing key is required. The workflow then verifies the published coordinate
actually resolves and the landing page actually loads before finishing.

## Project notes

See [the architecture decision record](docs/architecture-decision.md) for the
design review, alternatives, evidence, and implementation phases.
