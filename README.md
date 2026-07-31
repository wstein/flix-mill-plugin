# Flix Mill Plugin

Mill support for [Flix](https://flix.dev) projects. The plugin executes the
official `flix.jar` command-line interface from a Mill module.

## Status

This repository provides a Mill 1.x plugin implementation. It is deliberately
not published yet; use a local Mill dependency while developing it.

## Design

The project-local `flix.jar` is an explicit build input. This matches the
[Flix CLI setup](https://doc.flix.dev/getting-started.html): install Java 21+,
download `flix.jar` into the project directory, run `java -jar flix.jar init`,
then run `java -jar flix.jar run`.

The plugin will expose cached checking and packaging tasks and command tasks
for executing tests, programs, and `init`. It will run each command in the
Mill module directory, so Flix finds that module's `flix.toml` manifest.

## Use in a Mill build

After adding this library to your build's meta-build dependencies, import the
trait and extend it from the module that is the root of the Flix project:

```scala
import flixmill.FlixModule

object app extends FlixModule
```

Put `flix.jar`, `flix.toml`, `src/`, and (optionally) `test/` in `app/`. The
defaults may be overridden when a project manages its Java executable, compiler
JAR, or working directory differently:

```scala
object app extends FlixModule {
  override def flixJar = Task.Source(moduleDir / "tools" / "flix-0.75.1.jar")
}
```

| Mill task | Flix command |
| --- | --- |
| `app.check` | `check` |
| `app.test` | `test` |
| `app.run -- arg` | `run arg` |
| `app.buildPkg` | `build-pkg` |
| `app.init` | `init` |

`check` and `buildPkg` are cached tasks. `test`, `run`, and `init` are command
tasks because they perform user-directed actions. All task inputs include the
module directory and compiler JAR, so source, manifest, or compiler changes
invalidate cached work.

## Development

Requirements: Java 21+ and Mill 1.1.7. Run:

```text
mill flixMillPlugin.reformat
mill flixMillPlugin.checkFormat + flixMillPlugin.compile + flixMillPlugin.test
```

To include the real-compiler integration test, download a Flix JAR and run:

```text
FLIX_JAR=/absolute/path/to/flix.jar mill flixMillPlugin.test
```

The integration test creates its project in a temporary directory and does not
modify the repository.

## Publishing

Publication coordinates are intentionally not configured. A release needs an
owner-approved organization, repository URL, license, and version policy. Once
those are set, add `PublishModule` metadata and publish an artifact with the
`_mill1` platform suffix; the implementation already compiles against Mill
1.0.6 for Mill 1.x compatibility.

## Project notes

See [the architecture decision record](docs/architecture-decision.md) for the
design review, alternatives, evidence, and implementation phases.
