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

  /** Project inputs that should invalidate a Flix task.
    *
    * Flix discovers sources and `flix.toml` itself, so the module directory is the appropriate
    * source root to track.
    */
  def flixProjectFiles = Task.Source(moduleDir)

  /** Type-check the project without running it. */
  def check = Task {
    flixProjectFiles()
    invoke(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "check")
    PathRef(Task.dest)
  }

  /** Run the project's Flix test suite. */
  def test(args: Args) = Task.Command {
    flixProjectFiles()
    invoke(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "test", args.value)
  }

  /** Compile and run the project, forwarding arguments to Flix. */
  def run(args: Args) = Task.Command {
    flixProjectFiles()
    invoke(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "run", args.value)
  }

  /** Build the project's `.fpkg` artifact in its `artifact/` directory. */
  def buildPkg = Task {
    flixProjectFiles()
    invoke(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "build-pkg")
    PathRef(moduleDir / "artifact")
  }

  /** Initialize a new Flix project in the module directory. */
  def init(args: Args) = Task.Command {
    invoke(flixJavaExecutable(), flixJar().path, flixWorkingDirectory(), "init", args.value)
  }

  private def invoke(
      javaExecutable: String,
      jar: os.Path,
      workingDirectory: os.Path,
      subcommand: String,
      args: Seq[String] = Seq.empty
  ): Unit = {
    os.proc(FlixCommand.arguments(javaExecutable, jar, subcommand, args))
      .call(
        cwd = workingDirectory,
        stdout = os.Inherit,
        stderr = os.Inherit
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
}
