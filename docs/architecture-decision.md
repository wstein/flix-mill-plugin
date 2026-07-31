# ADR 001: Flix CLI integration for Mill

## Context

Flix documents a project-local workflow: Java 21+, a downloaded `flix.jar`,
`init`, and `run`. Mill plugins are JVM libraries that conventionally expose a
trait with Mill tasks; the plugin itself can be unit-tested with
`mill-testkit` and verified through an executable example.

## Design review

### Build engineer — explicit compiler artifact (9/10)

Use a `flixJar` source task that defaults to `flix.jar` in the module directory.
Downloading GitHub's mutable `releases/latest` URL inside a task would make
builds non-reproducible, disguise a network dependency, and leave no checksum
or version policy. The Flix setup guide already makes the JAR a project-local
input. The proposed approach tracks changes to that file naturally.

### Language-tooling engineer — automatic download (5/10)

Automatic setup lowers first-use friction and follows the copy-and-run CLI
story. However, it cannot simultaneously promise reproducibility unless a
version and digest are pinned. The initial plugin should not invent a release
management policy for Flix. A later, opt-in resolver can do that deliberately.

### Mill maintainer — task surface (9/10)

Expose `check`, `buildPkg`, and source discovery as regular tasks, returning
`PathRef`s when a task has an artifact. Expose `init`, `test`, and `run(args)`
as commands because they perform user-directed actions. Every task must run
with the module directory as its current working directory; otherwise nested
Mill modules would look for `flix.toml` in the wrong place.

### Release engineer — publication scope (8/10)

Compile against Mill 1.0.6 and use the `_mill1` platform suffix, following
Mill's compatibility guidance, while the development build itself uses the
current stable Mill 1.1.7. Do not add Maven Central metadata until the project
owner, organization, license, and repository URL are known; placeholder
publication metadata is worse than no publication configuration.

## Consensus

Implement the explicit-JAR design. Use a small trait, preserve the Flix CLI
subcommands rather than translating them into a second build model, and cover
the command assembly plus an end-to-end example. This rejects automatic
downloads for now (consensus 4/4).

## Phases and verification

1. Bootstrap the Mill plugin project and record the decision. Completed:
   the build compiles and formatting/linting commands are available.
2. Implement the `FlixModule` task trait and unit tests. Completed: command
   construction, argument forwarding, source tracking, and task outputs are
   covered.
3. Add a real Flix CLI integration test enabled by an explicit `FLIX_JAR`.
   Completed: it verifies `init`, `check`, `test`, `run`, and `build-pkg`
   against the selected compiler without committing a third-party binary.
4. Add `build` and `flixHelp`, narrow project inputs to the manifest and source
   roots, and return validated Flix outputs. Completed.
5. Add owner-approved publication metadata and consumer-build integration
   coverage. Pending ownership decisions.

## Future considerations

An opt-in, pinned compiler resolver is a reasonable enhancement. Publication
metadata is configured for local Ivy publication; remote-release credentials
and repository deployment are intentionally outside this build's scope.

## Output semantics review

### Mill task purist — introduce `compile` (6/10)

Mill users expect a `compile` target, and `mill-kotlin` can provide one because
it owns JVM compilation and returns Mill's `CompilationResult`. The proposal is
to map `compile` to Flix `build`. This would make task discovery familiar, but
would falsely imply that downstream Mill modules can consume Zinc analysis,
classpath, and classes using the normal JVM-module contract.

### Flix tooling engineer — preserve `build` (10/10)

Flix documents `check` as the faster validation command and `build` as the
bytecode-generating command. The observed compiler output is `build/class`.
The plugin should expose those names directly and return the validated Flix
output path, not a fabricated Mill compilation result. `build-pkg` writes
`artifact/<project-directory>.fpkg`; the name is based on the project directory,
not `[package].name`. A fresh compiler fixture verified both facts.

### Mill sandbox maintainer — do not return `PathRef` (9/10)

`PathRef` hashes its path. Mill rightfully rejects that read when a Flix
subprocess creates `build/` or `artifact/` outside `Task.dest`; neither is a
declared input. Returning validated `os.Path` values accurately represents
Flix-owned outputs and avoids self-invalidating generated directories.

### Release engineer — publish early, but test the published artifact (9/10)

Apache-2.0, `com.github.wstein`, version `0.1.0`, and the GitHub repository are
now explicit publication metadata. A consumer test must resolve the local Ivy
publication in a new Mill subprocess; direct trait tests would not detect
incorrect coordinates, POM metadata, or meta-build imports.

### Test engineer — make output names regression-tested (8/10)

The initial assumption that `build-pkg` creates `project.fpkg` was plausible
from generic documentation but failed against a real project named `app`.
The tests now cover CLI initialization plus build, test, run, package, and the
locally published consumer build. The observed filename rule should be retested
when Flix is upgraded.

## Consensus

Keep `check` and `build` as the public compilation-oriented API; defer
`compile` until Flix can supply a true Mill-native compilation model. Return
validated Flix-owned paths rather than `PathRef`s. Publish locally under the
approved Apache-2.0 metadata and require a consumer subprocess test in the
release gate. The package filename convention is an observed Flix 0.75.1
contract, not a permanent assumption.
