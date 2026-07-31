# Flix Mill Plugin

Mill support for [Flix](https://flix.dev) projects. The plugin executes the
official `flix.jar` command-line interface from a Mill module.

## Status

This repository provides a Mill 1.x plugin implementation, published as
`com.github.wstein:flix-mill-plugin_mill1_3`. See [Publishing](#publishing).

## Design

The project-local `flix.jar` is an explicit build input. This matches the
[Flix CLI setup](https://doc.flix.dev/getting-started.html): install Java 21+,
download `flix.jar` into the project directory, run `java -jar flix.jar init`,
then run `java -jar flix.jar run`.

The plugin exposes cached checking and packaging tasks, and command tasks for
executing tests, programs, and `init`. Each command runs in the module's Flix
working directory, so Flix finds that project's `flix.toml` manifest.

## Use in a Mill build

Add the plugin to your build's meta-build dependencies, import the trait, and
extend it from the module that is the root of the Flix project:

```scala
//| mvnDeps:
//| - com.github.wstein::flix-mill-plugin::0.1.0

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

`flixWorkingDirectory` is a plain `def`, not a task, because Mill's source tasks
cannot depend on one. Everything the plugin tracks or validates hangs off it, so
a single override relocates the compiler JAR, the manifest, the source roots,
and the generated output together:

```scala
object app extends FlixModule {
  override def flixWorkingDirectory = moduleDir / "flix"
}
```

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

Requirements: Java 21+ and Mill 1.1.7. Run:

```text
mill flixMillPlugin.reformat
mill flixMillPlugin.checkFormat + flixMillPlugin.compile + flixMillPlugin.test
```

To include the real-compiler integration test, download a Flix JAR and run:

```text
FLIX_JAR=/absolute/path/to/flix.jar mill flixMillPlugin.test
```

Before releasing, run the consumer gate. It publishes the plugin to a throwaway
repository, resolves it from a separate Mill subprocess, and builds real Flix
projects against it, so it catches wrong coordinates, POM metadata, or
meta-build imports that no unit test can see. It requires `FLIX_JAR` and fails
if the variable is unset, because a gate that quietly passes is worse than none:

```text
FLIX_JAR=/absolute/path/to/flix.jar mill flixMillPlugin.integration
```

Both suites create their projects in temporary directories. Neither modifies the
repository, and the gate publishes to a Mill-owned directory rather than
`~/.ivy2/local`.

## Publishing

The plugin is Apache-2.0 licensed and publishes as
`com.github.wstein:flix-mill-plugin_mill1_3:0.1.0`. Publish locally with:

```text
mill flixMillPlugin.publishLocal
```

The artifact uses the `_mill1` platform suffix and compiles against Mill 1.0.6
for Mill 1.x compatibility.

## Project notes

See [the architecture decision record](docs/architecture-decision.md) for the
design review, alternatives, evidence, and implementation phases.
