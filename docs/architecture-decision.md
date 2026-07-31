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

1. Bootstrap the Mill plugin project and record the decision. Verify the build
   compiles and formatting/linting commands are available.
2. Implement the `FlixModule` task trait and unit tests. Verify command
   construction, argument forwarding, source tracking, and task outputs.
3. Add a real Flix example and end-to-end test using a pinned fixture JAR.
   Verify `check`, `test`, `run`, and `build-pkg` through Mill.
4. Update usage documentation and perform the final quality pass.

## Future considerations

An opt-in, pinned compiler resolver and a publication module are reasonable
enhancements once artifact-version and ownership policy are agreed. Neither is
required for correctness of the project-local Flix workflow.
