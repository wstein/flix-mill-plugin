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
