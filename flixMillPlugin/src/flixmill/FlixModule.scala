package flixmill

import mill.*
import mill.api.{Args, FilesystemCheckerEnabled, PathRef}
import mill.util.Jvm

/** Mill tasks for a Flix project that uses a project-local `flix.jar`. */
trait FlixModule extends Module {

  /** Java executable used to launch the Flix compiler.
    *
    * Resolved on every run rather than cached, so upgrading or moving the JDK cannot leave a stale
    * absolute path behind. `Jvm.javaExe` appends the platform's executable suffix and falls back to
    * a `PATH` lookup when `java.home` holds no `java` binary.
    */
  def flixJavaExecutable = Task.Input { Jvm.javaExe }

  /** Directory in which Flix commands execute and locate `flix.toml`.
    *
    * Every path the plugin tracks or validates is relative to this directory, so overriding it
    * moves the compiler JAR, the manifest, the source roots, and the generated output together. It
    * is a plain `def` rather than a task because Mill's source tasks cannot depend on one.
    */
  def flixWorkingDirectory: os.Path = moduleDir

  /** Project-local Flix compiler JAR. */
  def flixJar = Task.Source(flixWorkingDirectory / "flix.jar")

  /** Flix manifest. */
  def flixManifest = Task.Source(flixWorkingDirectory / "flix.toml")

  /** Standard Flix source roots. Override to track additional project inputs. */
  def flixSourceDirectories =
    Task.Sources(flixWorkingDirectory / "src", flixWorkingDirectory / "test")

  /** Inputs that invalidate Flix compilation, excluding generated `build/` and `artifact/` files.
    */
  def flixProjectInputs = Task {
    flixManifest()
    flixSourceDirectories()
  }

  /** Type-check the project without running it. */
  def check = Task {
    flixProjectInputs()
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, flixWorkingDirectory, "check")
    Task.dest
  }

  /** Compile the project and return Flix's generated JVM class directory. */
  def build = Task {
    flixProjectInputs()
    val projectDirectory = flixWorkingDirectory
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, projectDirectory, "build")
    val classes = projectDirectory / "build" / "class"
    require(os.exists(classes), s"Flix build completed without creating $classes")
    FlixArtifact.outputPathRef(classes)
  }

  /** Run the project's Flix test suite. */
  def test(args: Args) = Task.Command {
    flixProjectInputs()
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "test",
      args.value
    )
  }

  /** Compile and run the project, forwarding arguments to Flix. */
  def run(args: Args) = Task.Command {
    flixProjectInputs()
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "run",
      args.value
    )
  }

  /** The compiled Flix classes, as a classpath entry for a JVM module.
    *
    * A `FlixModule` is not a `JavaModule`, so it cannot appear in another module's `moduleDeps`.
    * This is what a JVM module in the same build depends on instead:
    *
    * {{{
    * object greeter extends FlixModule
    *
    * object app extends JavaModule {
    *   def unmanagedClasspath = Task { greeter.flixClasspath() }
    * }
    * }}}
    *
    * What a Java caller finds there is whatever the Flix project marked `@Export`: a class per
    * module carrying `public static` methods. Flix code that is not exported is compiled into the
    * same directory but named for the compiler's convenience, so it is not something to call.
    *
    * The class directory rather than [[buildJar]], because it needs no packaging step and Mill puts
    * directories on a classpath as readily as jars. Use the jar when the artifact leaves the build.
    */
  def flixClasspath = Task { Seq(build()) }

  /** Compile-only Java stubs for the project's `@Export`-ed defs.
    *
    * This is pass 0 of joint compilation, and it exists because Flix and Java can reference each
    * other in a way that has no valid build order: a Java class calling an exported Flix def needs
    * Flix codegen to have run, while a Flix module calling that Java class needs its class files.
    * Handing `javac` a stub facade turns the cycle into a sequence.
    *
    * Written into `Task.dest` rather than the project directory, because unlike `build/` and
    * `artifact/` these are Mill's output and not Flix's: they are consumed by a Java compile that
    * Mill schedules, and they must never survive into anything that runs.
    *
    * Deliberately depends on nothing but the sources. Pass 0 runs before the project can compile --
    * that is the situation it is for -- so it must not wait on `build()`, and `flix stubs` does not
    * resolve dependencies for the same reason.
    */
  def flixStubSources = Task {
    flixProjectInputs()
    val destination = Task.dest / "java"
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "stubs",
      Seq("--out", destination.toString)
    )
    PathRef(destination)
  }

  /** Build the project's JVM `.jar` artifact in its `artifact/` directory.
    *
    * Distinct from [[buildPkg]], which produces the `.fpkg` that other *Flix* projects depend on.
    * This one is an ordinary jar, for consumers that have no idea Flix was involved.
    */
  def buildJar = Task {
    flixProjectInputs()
    val projectDirectory = flixWorkingDirectory
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, projectDirectory, "build-jar")
    val jarFile = FlixArtifact.jarFile(projectDirectory)
    require(os.exists(jarFile), s"Flix build-jar completed without creating $jarFile")
    FlixArtifact.outputPathRef(jarFile)
  }

  /** Build the project's `.fpkg` artifact in its `artifact/` directory. */
  def buildPkg = Task {
    flixProjectInputs()
    val projectDirectory = flixWorkingDirectory
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, projectDirectory, "build-pkg")
    val packageFile = FlixArtifact.packageFile(projectDirectory)
    require(os.exists(packageFile), s"Flix build-pkg completed without creating $packageFile")
    FlixArtifact.outputPathRef(packageFile)
  }

  /** Initialize a new Flix project in the module directory. */
  def init(args: Args) = Task.Command {
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "init",
      args.value
    )
  }

  /** Show the help text supported by the configured Flix compiler. */
  def flixHelp(args: Args) = Task.Command {
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "--help",
      args.value
    )
  }
}

private[flixmill] object FlixCommand {
  def arguments(
      javaExecutable: String,
      jar: os.Path,
      subcommand: String,
      args: Seq[String]
  ): Seq[String] =
    Seq(javaExecutable, "-jar", jar.toString, subcommand) ++ args

  def execute(
      javaExecutable: String,
      jar: os.Path,
      workingDirectory: os.Path,
      subcommand: String,
      args: Seq[String] = Seq.empty
  ): Unit =
    os.proc(arguments(javaExecutable, jar, subcommand, args))
      .call(
        cwd = workingDirectory,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
}

private[flixmill] object FlixArtifact {
  def packageFile(projectDirectory: os.Path): os.Path =
    projectDirectory / "artifact" / s"${projectDirectory.last}.fpkg"

  /** Flix names the jar after the working directory too, on the same observed contract as `.fpkg`.
    */
  def jarFile(projectDirectory: os.Path): os.Path =
    projectDirectory / "artifact" / s"${projectDirectory.last}.jar"

  /** Signature for Flix-owned output that lives outside `Task.dest`.
    *
    * Mill's filesystem checker only lets a task read its own `Task.dest` or an upstream `PathRef`,
    * so hashing what Flix just wrote into the project directory requires suspending it. The
    * resulting `PathRef` is revalidated whenever a cached result is read back, so deleting the
    * output re-runs the task instead of replaying a path to a missing file.
    */
  def outputPathRef(path: os.Path): PathRef =
    FilesystemCheckerEnabled.withValue(false)(PathRef(path).withRevalidateOnce)
}
