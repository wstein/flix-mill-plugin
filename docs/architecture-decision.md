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
   coverage. Completed: the plugin publishes as
   `com.github.wstein:flix-mill-plugin_mill1_3:0.1.0` and a consumer gate
   resolves it from a Mill subprocess.
6. Correct JVM resolution, output signatures, and working-directory handling,
   and make the consumer gate non-optional. Completed: see
   [Output signature review](#output-signature-review).

## Future considerations

An opt-in, pinned compiler resolver is a reasonable enhancement. Publication
metadata is configured for local publication; remote-release credentials and
repository deployment are intentionally outside this build's scope.

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

This decision was later reversed; see
[Output signature review](#output-signature-review).

### Release engineer — publish early, but test the published artifact (9/10)

Apache-2.0, `com.github.wstein`, version `0.1.0`, and the GitHub repository are
now explicit publication metadata. A consumer test must resolve the publication
in a new Mill subprocess; direct trait tests would not detect incorrect
coordinates, POM metadata, or meta-build imports.

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

## Output signature review

This review revisits the decision above after a code review found that returning
plain paths cannot express "this output is gone".

### Mill sandbox maintainer — reversed: return a revalidating `PathRef` (9/10)

Returning an `os.Path` leaves nothing for Mill to check. The `require` guards run
only on a cache miss, and the generated directories are deliberately not inputs,
so after `rm -rf build/` — or any clean that spares Mill's `out/` — `build` and
`buildPkg` replayed a cached success naming a file that no longer existed.

The earlier objection was accurate about the mechanism but wrong about the
conclusion. Mill's filesystem checker does reject the hash, and it exposes
`FilesystemCheckerEnabled` precisely so a build can suspend the check where it
knows better. Suspending it to sign output the task itself just produced is
narrow and honest; it grants no read the task was not already entitled to make.

`PathRef.Revalidate.Once` is what closes the hole: Mill re-checks the signature
when it reads the cached result back and re-runs the task when it no longer
matches. Mill's default of `Revalidate.Never` would silently restore the bug, so
a unit test pins the policy. The self-invalidation the earlier review feared does
not occur, because the output is signed but never declared an input.

### Build engineer — one working directory, one project (9/10)

`flixWorkingDirectory` governed where the compiler ran while `flixJar`,
`flixManifest`, and `flixSourceDirectories` resolved against `moduleDir`.
Overriding it produced a build that watched files nobody compiled and compiled
files nobody watched. Mill's source tasks are graph leaves and cannot depend on
a task, so the working directory becomes a plain `def` and every tracked or
generated path derives from it.

### Release engineer — a gate that can abstain is not a gate (10/10)

The consumer test was wrapped in `sys.env.get("FLIX_JAR").foreach(...)`, so on
any machine without that variable it reported success while publishing,
resolving, and building nothing. It now fails when the variable is missing.
Publication moved from `~/.ivy2/local` to `publishLocalTestRepo`, reached through
`MILL_USER_TEST_REPO`, so the gate cannot write outside the build or resolve a
leftover artifact; the fixture carries a placeholder version substituted per run.

### Test engineer — assert behaviour, not the implementation (8/10)

The package-path test restated its implementation character for character and
could never fail meaningfully. Coverage now targets what can break: the signature
policy, the checker suspension, the working-directory contract, and — through the
consumer gate — a deleted output forcing a rebuild and an overridden working
directory relocating the whole project.

## Revised consensus

Return revalidating `PathRef`s for Flix-owned output, suspending Mill's
filesystem checker only to sign output the task just produced. Derive every
tracked and generated path from `flixWorkingDirectory`. Keep the consumer test as
a release gate that fails rather than abstains, publishing to a throwaway
repository. This supersedes the "do not return `PathRef`" position above (4/4).
