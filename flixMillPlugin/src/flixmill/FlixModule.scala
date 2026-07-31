package flixmill

import mill.*
import mill.api.{Args, PathRef}

/** Mill tasks for a Flix project that uses a project-local `flix.jar`. */
trait FlixModule extends Module {

  /** Java executable used to launch the Flix compiler. */
  def flixJavaExecutable = Task { "java" }

  /** Project-local Flix compiler JAR. */
  def flixJar = Task.Source(moduleDir / "flix.jar")

  /** Directory in which Flix commands execute and locate `flix.toml`. */
  def flixWorkingDirectory = Task { moduleDir }

  /** Flix manifest. */
  def flixManifest = Task.Source(moduleDir / "flix.toml")

  /** Standard Flix source roots. Override to track additional project inputs. */
  def flixSourceDirectories = Task.Sources(moduleDir / "src", moduleDir / "test")

  /** Inputs that invalidate Flix compilation, excluding generated `build/` and `artifact/` files.
    */
  def flixProjectInputs = Task {
    flixManifest()
    flixSourceDirectories()
  }

  /** Type-check the project without running it. */
  def check = Task {
    flixProjectInputs()
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "check")
    PathRef(Task.dest)
  }

  /** Compile the project and return Flix's generated JVM class directory. */
  def build = Task {
    flixProjectInputs()
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "build")
    val classes = moduleDir / "build" / "class"
    require(os.exists(classes), s"Flix build completed without creating $classes")
    PathRef(classes)
  }

  /** Run the project's Flix test suite. */
  def test(args: Args) = Task.Command {
    flixProjectInputs()
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory(),
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
      flixWorkingDirectory(),
      "run",
      args.value
    )
  }

  /** Build the project's `.fpkg` artifact in its `artifact/` directory. */
  def buildPkg = Task {
    flixProjectInputs()
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "build-pkg")
    PathRef(FlixArtifact.packageFile(moduleDir / "artifact"))
  }

  /** Initialize a new Flix project in the module directory. */
  def init(args: Args) = Task.Command {
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory(),
      "init",
      args.value
    )
  }

  /** Show the help text supported by the configured Flix compiler. */
  def flixHelp(args: Args) = Task.Command {
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory(),
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
  def packageFile(artifactDirectory: os.Path): os.Path = {
    val packages =
      if (os.isDir(artifactDirectory)) os.list(artifactDirectory).filter(_.ext == "fpkg")
      else Seq.empty

    packages match {
      case Seq(file) => file
      case Seq()     =>
        throw new IllegalStateException(s"Flix did not create an .fpkg file in $artifactDirectory")
      case _ =>
        throw new IllegalStateException(s"Flix created multiple .fpkg files in $artifactDirectory")
    }
  }
}
