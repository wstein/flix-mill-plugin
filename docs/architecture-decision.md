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
   `io.github.wstein:flix-mill-plugin_mill1_3:0.1.0` and a consumer gate
   resolves it from a Mill subprocess.
6. Correct JVM resolution, output signatures, and working-directory handling,
   and make the consumer gate non-optional. Completed: see
   [Output signature review](#output-signature-review).
7. Run both suites on every push and keep every pinned version current.
   Completed: see
   [Continuous integration review](#continuous-integration-review).

8. Move the namespace and publish to Maven Central from a tag. Completed: see
   [Publication review](#publication-review).

## Future considerations

An opt-in, pinned compiler resolver is a reasonable enhancement.

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

Apache-2.0, `io.github.wstein`, version `0.1.0`, and the GitHub repository are
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

### Test engineer — assert behavior, not the implementation (8/10)

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

## Continuous integration review

Phases 1 to 6 left the build verifiable but unverified: every command was run by
hand. This review decides how the repository runs them itself.

### Build engineer — bootstrap scripts, not a setup action (9/10)

A `setup-mill`-style action is one line shorter, but it introduces a third-party
step that must itself be kept current and that decides which Mill version to
install. Mill's own `mill` and `mill.bat` bootstrap scripts read `.mill-version`
and provision the JDK named by `jvmVersion`, so the workflow needs no
`setup-java` step and CI cannot drift from the version a contributor uses. The
cost is a vendored script; `./mill updateMillScripts <version>` regenerates it,
and the version it pins is documented in the README.

### Release engineer — the gate is worthless if it never runs (10/10)

Phase 6 made the consumer gate fail rather than abstain when `FLIX_JAR` is
unset. That only matters if something sets the variable. CI downloads a Flix
release into the runner's temporary directory and exports it, so both the
real-compiler suite and the publish-resolve-build gate execute on every push.

The download pins one release rather than `releases/latest`. This is the same
reasoning that kept automatic downloads out of the plugin, applied to the
workflow: the `artifact/<project-directory>.fpkg` naming rule the plugin depends
on is an observed contract of that release, and a floating URL would turn an
upstream release into a surprise failure on an unrelated pull request. Upgrading
Flix should be a deliberate commit that re-tests the naming rule.

### CI engineer — split fast feedback from the slow gate (8/10)

Running everything in one job would put a 30-plus-second compiler download and a
Mill subprocess in front of a formatting error. Two jobs run concurrently: one
reports formatting, compilation, and unit failures quickly, the other proves the
published artifact works. Both share a Mill and Coursier cache keyed on
`.mill-version` and `build.mill`.

### Dependency maintainer — Renovate, since Dependabot cannot see Mill (8/10)

Dependabot was the first choice and was rejected on inspection. It has no Mill
ecosystem and cannot be taught one, so it would have maintained the workflow's
actions and nothing else: `.mill-version`, the bootstrap scripts, the Scala
version, and the pinned Flix release would all have stayed manual, which is most
of what this repository actually pins.

Renovate has no Mill manager either, but it has `customManagers`, which closes
the gap. Running both would have produced duplicate action pull requests —
they share no state and neither closes the other's work — so Dependabot is
removed rather than kept alongside.

The rule each custom manager follows is that one dependency name covers every
file holding that version, so an upgrade is never half-applied: Mill moves
`.mill-version` and both bootstrap scripts together, and Flix moves the CI pin
and the README statements with it. A Flix bump is therefore a pull request whose
own CI run re-tests the `.fpkg` naming rule the plugin depends on — the gate
grades the upgrade that proposes it.

Two things are deliberately excluded. `millVersion` in `build.mill` is the
oldest Mill 1.x the published artifact supports, not a version to keep current;
letting a bot raise it would silently drop consumers. `github-runners` is
disabled because `ubuntu-latest` is intentional and pinning the image would add
churn this build gains nothing from.

## Continuous integration consensus

Check the Mill bootstrap scripts into the repository and drive CI with them.
Split the workflow into fast unit feedback and a slower job that runs the
real-compiler suite and the consumer gate against a pinned Flix release. Use
Renovate rather than Dependabot, with custom managers covering every version
this repository pins and explicit exclusions for the two that must not float
(4/4).

## Publication review

Phase 5 approved publication metadata without checking whether the namespace it
chose could ever be published. This review corrects that and takes the project
to a real release path.

### Release engineer — reversed: the namespace had to move (10/10)

`com.github.wstein` was unpublishable. Sonatype stopped accepting `com.github.*`
in April 2021, on GitHub's own request, and there is no route to verify it: the
portal checks for a DNS TXT record on the exact domain, which here means
`github.com`. The supported form for a publisher without a domain is
`io.github.<user>`, verified against the GitHub account that already owns the
repository.

The cost of finding this late would have been unbounded. Maven Central
publications are immutable — a wrong group ID cannot be withdrawn, only
orphaned and superseded. Because nothing had been published remotely, the fix
was a rename across four files plus the consumer gate re-running. The same
mistake discovered one release later would have been permanent.

`de.wstein`, verified by a TXT record on a domain the author controls, was the
alternative. `io.github.wstein` was chosen for having no DNS dependency and no
renewal risk.

### Build engineer — `publish` was already the wrong task (9/10)

The `publish` task inherited from `PublishModule` targets legacy OSSRH, which
Sonatype retired; Mill's own documentation for the task says to use Sonatype
Central publishing instead. The module therefore extends
`SonatypeCentralPublishModule`. Nothing else in the build changes: the POM Mill
already generated satisfies every field Central validates, and sources and
Scaladoc jars are produced without configuration.

### Release engineer — a tag is a claim that must be checked (9/10)

`publishVersion` is a literal in `build.mill` while the release is triggered by
a tag, so the two can disagree. Deriving the version from the tag with
`VcsVersionModule` would remove the divergence but make every local build carry
a commit-distance suffix. The workflow instead refuses to run from anything but
a tag, and refuses a tag whose name does not match `publishVersion`. Given
immutability, failing loudly before upload is worth more than the convenience.

The workflow re-runs the consumer gate before uploading even though CI already
ran it on the same commit. A tag can point at a commit CI never saw, and this is
the last point at which a mistake is still free.

### Release engineer — upload, then stop (8/10)

`sonatypeCentralShouldRelease` is false, so a release uploads a signed bundle
and leaves it at `VALIDATED` for a human to publish. A deployment in that state
can still be dropped; one that has been published cannot. Automating the final
click would trade a few seconds against the only remaining chance to inspect
what is about to become permanent.

## Publication consensus

Publish under `io.github.wstein` through the Central portal using
`SonatypeCentralPublishModule`. Gate releases on a tag that matches
`publishVersion` and on the consumer gate, and stop at an uploaded bundle rather
than releasing automatically. This supersedes the `com.github.wstein` coordinate
approved in phase 5 (4/4).

## Distribution channel review

The first real release (`v0.1.0`) reached the "upload to Central" step with no
Sonatype account provisioned — `gh secret list` was empty, so the four secrets
the previous section's design assumed never existed. Modeled on
`wstein/flix-spec`'s already-working GitHub Pages Maven mirror, that channel was
added alongside Central in the same release job. It was reached first, needed
no account, and both a real Mill-consumer resolution and the landing page were
verified to work against it in CI before Central was ever touched.

### Release engineer — drop Central, keep only GitHub Pages (9/10)

The project has never once actually published to Central: no account was ever
provisioned, and every real release attempt failed at that step. Central's
value proposition — the namespace every Mill/sbt build resolves with zero
configuration — is real, but it is not free: an approved account, a primary
PGP key mailed to a keyserver, four long-lived secrets, and a human clicking
"Publish" in a portal UI on every release. GitHub Pages costs none of that and
is already the channel that has actually been exercised end to end. Keeping
both means maintaining machinery for a channel with a 0% success rate against
one with a 100% success rate. Dropped `SonatypeCentralPublishModule` for plain
`PublishModule` — `publishM2Local`, `pomSettings`, `artifactId`, `artifactName`
are all `PublishModule` members, so nothing the GitHub Pages path depends on
was lost.

### Build engineer — the `io.github.wstein` namespace still earns its keep (7/10)

Losing Central does not make the phase-6 namespace fix moot: `io.github.*`
still reads as a real, GitHub-verified coordinate to anyone resolving it from
the GitHub Pages repository, and readopting `com.github.*` would just
reintroduce the original problem if Central publishing is ever revisited.
Kept as-is.

## Distribution channel consensus

Publish only to the `gh-pages` GitHub Pages Maven mirror. `build.mill` uses
plain `PublishModule`, not `SonatypeCentralPublishModule`; the release workflow
no longer has a Central-upload step, and no Sonatype/PGP secrets are expected
to exist in the repository. This supersedes the "Publish under `io.github.wstein`
through the Central portal" line of the publication consensus above; the
namespace choice itself is unaffected. Revisiting Central publishing is not
ruled out, but it is out of scope until asked for again.

## Build integration review

The plugin ran Flix but shared nothing with the rest of a Mill build. Package
metadata and dependencies were declared twice — once in `flix.toml`, once in
`build.mill` — and a JVM module in the same build had no way to call Flix code.

### Build engineer — Mill owns the manifest, but only when asked (9/10)

A Flix project and the Mill build wrapping it carry the same package metadata
and the same dependency list. Maintaining both by hand means they drift, and
the drift is quiet: Flix resolves what its manifest says while Mill reports
what the build file says, and neither notices the disagreement. `flix.toml`
should be generated.

It must be opt-in. Every existing project has a hand-written manifest, and
generating one means writing into the project directory rather than into
`Task.dest`. Added `FlixManifestModule` as a separate trait, so `FlixModule`
alone keeps tracking a hand-written file. A generated manifest carries a marker
comment on its first line and generation *fails* rather than overwriting a
`flix.toml` without it — a hand-written manifest can hold metadata that exists
nowhere else, so replacing it silently destroys the only copy.

### Mill maintainer — the write must admit it is a write (8/10)

Mill forbids a task writing outside its `Task.dest`, and this writes into the
project directory because Flix looks for a manifest only by name in the
directory it runs in. Suspending the check is the same concession
`FlixArtifact.outputPathRef` already makes for reading Flix's output, and the
asymmetry is worth stating: `build/` and `artifact/` land in the project
directory too and are never caught, because Flix writes them from a subprocess
the checker cannot see. Observing the rule here would be a difference in who
holds the pen, not in what is written. Accepted, with that reasoning recorded at
the call site.

### Dependency maintainer — declare dependencies in Mill's own syntax (9/10)

Taking coordinate strings would have reproduced the duplication the generated
manifest exists to remove. `flixMvnDeps` takes Mill's `Dep`, so a Flix project
writes `mvn"org.postgresql:postgresql:42.7.3"` exactly as any other module
would.

A Flix manifest names a dependency by group, artifact and version and has
nowhere to put anything else, so a `Dep` carrying more is rejected rather than
written out shorn of what cannot be expressed. Cross-versioned deps would name
an artifact that does not exist, since Flix appends no Scala suffix; exclusions
and classifiers would resolve something other than what the build asked for.
Refusing is better than either, and each refusal is tested — a guard that is
never exercised is a guess about an API rather than a check on it.

### Mill maintainer — a classpath entry, not a fake `JavaModule` (9/10)

Making `FlixModule` extend `JavaModule` so it could appear in `moduleDeps` would
be the "second build model" the original consensus rejected: it would inherit a
compile/test/publish surface that Flix does not have and cannot honour. Added
`flixClasspath` instead, so a JVM module writes one line —
`def unmanagedClasspath = Task { greeter.flixClasspath() }` — and `buildJar`
beside it for when the artifact leaves the build. What a Java caller finds
there is whatever the Flix project marked `@Export`.

### Test engineer — compiling is not calling (10/10)

A test that only compiles the Java module would pass against an empty classpath
entry if the Flix build silently produced nothing. The integration gate now runs
the JVM module and asserts its output, so the exported class has to be on the
classpath under the name a Java caller writes.

The fixture is limited to primitive parameters and returns on purpose: the
pinned Flix release accepts nothing wider in an exported signature, and a
fixture that only compiles against a newer compiler is not a gate.

## Build integration consensus

Generate `flix.toml` from the Mill build behind an opt-in trait that never
overwrites a manifest it did not write; declare Flix's Maven dependencies as
Mill `Dep`s and reject what the manifest cannot express; expose Flix output to
JVM modules as a classpath entry and a jar rather than by pretending a Flix
project is a `JavaModule`. The release gate runs a Java module against Flix
output rather than merely compiling it (consensus 5/5).
