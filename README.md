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

## Development

Requirements: Java 21+ and Mill 1.1.7. Run:

```text
mill flixMillPlugin.reformat
mill flixMillPlugin.checkFormat
mill flixMillPlugin.compile
mill flixMillPlugin.test
```

To include the real-compiler integration test, download a Flix JAR and run:

```text
FLIX_JAR=/absolute/path/to/flix.jar mill flixMillPlugin.test
```

The integration test creates its project in a temporary directory and does not
modify the repository.

## Project notes

See [the architecture decision record](docs/architecture-decision.md) for the
design review, alternatives, evidence, and implementation phases.
